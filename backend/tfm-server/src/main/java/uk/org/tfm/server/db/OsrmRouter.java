package uk.org.tfm.server.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.proto.v1.LatLng;
import uk.org.tfm.server.config.Config;
import uk.org.tfm.server.service.OsrmInfo;

/**
 * Snaps an ordered list of stop coordinates onto real roads via OSRM, for services whose operator
 * publishes no GTFS shape (e.g. Centrebus around Melton). Results are cached by the coordinate
 * signature, so all trips on the same route pattern share one OSRM call. Failure → empty list, and
 * the caller falls back to straight lines. Swap {@code OSRM_URL} to a self-hosted instance for
 * prod.
 */
@Singleton
public final class OsrmRouter implements OsrmInfo {

  private static final Logger log = LoggerFactory.getLogger(OsrmRouter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final String baseUrl;
  private final boolean enabled;
  private final ConcurrentHashMap<String, SnappedPath> cache = new ConcurrentHashMap<>();

  @Inject
  public OsrmRouter(Config config) {
    String url = config.osrmUrl();
    this.baseUrl = (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    this.enabled = !this.baseUrl.isBlank();
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /** Whether road-snapping is configured (OSRM_URL non-blank). */
  public boolean enabled() {
    return enabled;
  }

  /**
   * Cheap liveness probe: does OSRM actually answer? Returns true if the server responds to a
   * trivial request at all (even an error status), false on a transport failure (connection
   * refused, timeout, DNS). Lets the loader skip a whole road-snapping cycle when OSRM is down
   * rather than grinding through hundreds of doomed, politeness-paced calls.
   */
  public boolean isReachable() {
    if (!enabled) {
      return false;
    }
    // A trivial nearest query (Melton Mowbray); we only care whether OSRM answers, not the result.
    URI uri = URI.create(baseUrl + "/nearest/v1/driving/-0.886,52.766?number=1");
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
    try {
      http.send(req, HttpResponse.BodyHandlers.discarding());
      return true; // any HTTP response means OSRM is up
    } catch (Exception e) {
      log.warn("OSRM not reachable at {}: {}", baseUrl, e.getMessage());
      return false;
    }
  }

  /** Road geometry plus the OSM node id at each vertex (same length, same order). */
  public record SnappedPath(List<LatLng> geometry, long[] nodes) {
    static final SnappedPath EMPTY = new SnappedPath(List.of(), new long[0]);
  }

  /**
   * Like {@link #snapToRoads} but also returns the OSM node id at each geometry vertex. The node
   * ids are the shared topology that route bundling keys off: two routes on the same road return
   * the same node ids on that stretch. Returns {@link SnappedPath#EMPTY} on any failure or
   * misalignment.
   */
  public SnappedPath snapWithNodes(List<double[]> lngLat) {
    return snap(lngLat);
  }

  /**
   * Map-matches an ordered GPS-style trace (a route's published GTFS shape, sampled to {@code [lng,
   * lat]} points) onto the OSM road graph via OSRM {@code /match}, returning the matched road
   * geometry with the OSM node id at each vertex — same shape as {@link #snapWithNodes}, so route
   * bundling keys off it identically. Unlike stop-to-stop {@code /route} snapping, this follows the
   * operator's actual filed road even where no stop pins it (e.g. a through road with no stop), so
   * the bundled overlay matches the highlighted route. Returns {@link SnappedPath#EMPTY} on any
   * failure, misalignment, or a trace OSRM can't match as one continuous run (caller then falls
   * back to stop snapping).
   */
  public SnappedPath matchWithNodes(List<double[]> lngLat) {
    return match(lngLat);
  }

  /**
   * The single OSRM call shared by both public entry points: snaps the ordered coords onto roads
   * with node annotations, prunes car-park / access-road spurs, and caches the result by coordinate
   * signature. Always requesting {@code annotations=nodes} means spur pruning applies to
   * <em>every</em> snapped route, not just the shared-road overlay.
   *
   * <p>OSRM returns one continuous geometry but per-leg node arrays whose boundary node is shared
   * with the next leg; we drop that duplicate so {@code nodes} aligns 1:1 with the geometry.
   */
  private SnappedPath snap(List<double[]> lngLat) {
    if (!enabled || lngLat.size() < 2) {
      return SnappedPath.EMPTY;
    }
    String coordsPath = coordsPath(lngLat);
    String key = "r:" + coordsPath;
    SnappedPath cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    try {
      URI uri =
          URI.create(
              baseUrl
                  + "/route/v1/driving/"
                  + coordsPath
                  + "?overview=full&geometries=geojson&annotations=nodes");
      JsonNode root = get(uri);
      if (root == null) {
        return SnappedPath.EMPTY;
      }
      SnappedPath result = parseLegged(root.path("routes").path(0));
      if (result == null) {
        return SnappedPath.EMPTY;
      }
      cache.put(key, result); // cache only successful results
      return result;
    } catch (Exception e) {
      log.debug("OSRM snap failed ({} points): {}", lngLat.size(), e.getMessage());
      return SnappedPath.EMPTY;
    }
  }

  /**
   * Map-matches a trace via OSRM {@code /match}. {@code gaps=ignore} keeps a (timestamp-free) trace
   * as one run; {@code tidy=true} drops near-duplicate points. We require exactly one matching — a
   * trace OSRM had to split (a stretch it couldn't match) would otherwise stitch a false straight
   * edge across the gap, so we bail and let the caller fall back to stop snapping.
   */
  private SnappedPath match(List<double[]> lngLat) {
    if (!enabled || lngLat.size() < 2) {
      return SnappedPath.EMPTY;
    }
    String coordsPath = coordsPath(lngLat);
    String key = "m:" + coordsPath;
    SnappedPath cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    try {
      URI uri =
          URI.create(
              baseUrl
                  + "/match/v1/driving/"
                  + coordsPath
                  + "?overview=full&geometries=geojson&annotations=nodes&tidy=true&gaps=ignore");
      JsonNode root = get(uri);
      if (root == null) {
        return SnappedPath.EMPTY;
      }
      JsonNode matchings = root.path("matchings");
      if (matchings.size() != 1) {
        return SnappedPath.EMPTY; // split (or no) match — fall back to stop snapping
      }
      SnappedPath result = parseLegged(matchings.path(0));
      if (result == null) {
        return SnappedPath.EMPTY;
      }
      cache.put(key, result);
      return result;
    } catch (Exception e) {
      log.debug("OSRM match failed ({} points): {}", lngLat.size(), e.getMessage());
      return SnappedPath.EMPTY;
    }
  }

  /**
   * Builds the OSRM {@code lng,lat;lng,lat} coordinate path from ordered {@code [lng, lat]} pairs.
   */
  private static String coordsPath(List<double[]> lngLat) {
    StringJoiner path = new StringJoiner(";");
    for (double[] c : lngLat) {
      path.add(c[0] + "," + c[1]);
    }
    return path.toString();
  }

  /**
   * Sends a GET, returning the parsed JSON body iff HTTP 200 and {@code code == "Ok"}, else null.
   */
  private JsonNode get(URI uri) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      return null;
    }
    JsonNode root = MAPPER.readTree(resp.body());
    return "Ok".equals(root.path("code").asText()) ? root : null;
  }

  /**
   * Parses a legged geometry object (an OSRM {@code route} or {@code matching} — both carry {@code
   * geometry.coordinates} + per-leg {@code annotation.nodes}) into a spur-pruned {@link
   * SnappedPath}. Concatenates the per-leg node arrays, dropping each leg's first node (shared with
   * the prior leg) so nodes align 1:1 with the continuous geometry. Returns null on misalignment.
   */
  private static SnappedPath parseLegged(JsonNode legged) {
    JsonNode coords = legged.path("geometry").path("coordinates");
    List<Long> nodes = new ArrayList<>(coords.size());
    JsonNode legs = legged.path("legs");
    for (int i = 0; i < legs.size(); i++) {
      JsonNode legNodes = legs.path(i).path("annotation").path("nodes");
      for (int j = (i == 0 ? 0 : 1); j < legNodes.size(); j++) {
        nodes.add(legNodes.get(j).asLong());
      }
    }
    if (nodes.size() != coords.size() || coords.size() < 2) {
      return null; // misaligned — don't guess
    }
    List<LatLng> geom = new ArrayList<>(coords.size());
    long[] nodeIds = new long[coords.size()];
    for (int i = 0; i < coords.size(); i++) {
      JsonNode c = coords.get(i);
      geom.add(LatLng.newBuilder().setLat(c.get(1).asDouble()).setLng(c.get(0).asDouble()).build());
      nodeIds[i] = nodes.get(i);
    }
    return pruneSpurs(geom, nodeIds);
  }

  /**
   * Per-leg cap for spur removal: a there-and-back shorter than this is treated as a car-park /
   * retail-access poke (a stop sitting off the road), not part of the route, and pruned.
   */
  private static final double SPUR_MAX_M = 120;

  /**
   * Removes short out-and-back spurs from a snapped path. OSRM's {@code driving} profile dives down
   * a car-park or retail access road to reach a stop that sits inside it, then doubles straight
   * back — visible as a node {@code A} immediately revisited ({@code …A B A…}, possibly several
   * vertices deep). We collapse such a U-turn when its tip leg is short, iterating so multi-vertex
   * spurs peel away. The {@code nodes[i-1]==nodes[i+1]} trigger only fires on a genuine doubleback,
   * so ordinary forward geometry is never touched; the length cap spares legitimate (longer)
   * terminus turnarounds. Geometry and node ids stay aligned 1:1 throughout.
   */
  private static SnappedPath pruneSpurs(List<LatLng> geom, long[] nodeIds) {
    List<LatLng> g = new ArrayList<>(geom);
    List<Long> n = new ArrayList<>(nodeIds.length);
    for (long id : nodeIds) {
      n.add(id);
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int i = 1; i + 1 < n.size(); i++) {
        if (n.get(i - 1).equals(n.get(i + 1))
            && haversine(g.get(i - 1), g.get(i)) + haversine(g.get(i), g.get(i + 1)) < SPUR_MAX_M) {
          g.remove(i + 1); // the returning duplicate of vertex i-1
          n.remove(i + 1);
          g.remove(i); // the spur tip
          n.remove(i);
          changed = true;
          break;
        }
      }
    }
    long[] ids = new long[n.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = n.get(i);
    }
    return new SnappedPath(List.copyOf(g), ids);
  }

  private static double haversine(LatLng a, LatLng b) {
    double r = 6_371_000;
    double dLat = Math.toRadians(b.getLat() - a.getLat());
    double dLng = Math.toRadians(b.getLng() - a.getLng());
    double s =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(a.getLat()))
                * Math.cos(Math.toRadians(b.getLat()))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return 2 * r * Math.asin(Math.min(1, Math.sqrt(s)));
  }

  /**
   * Returns road geometry through the given points (each {@code [lng, lat]}), with car-park /
   * access- road spurs pruned, or an empty list if disabled, too few points, or the request fails.
   */
  public List<LatLng> snapToRoads(List<double[]> lngLat) {
    return snap(lngLat).geometry();
  }
}
