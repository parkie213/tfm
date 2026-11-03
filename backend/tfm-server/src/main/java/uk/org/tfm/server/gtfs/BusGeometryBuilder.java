package uk.org.tfm.server.gtfs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.storage.BusGeometryStore;
import uk.org.tfm.model.storage.LoadState;
import uk.org.tfm.model.storage.LoadStateStore;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.timetable.BusRouteLabel;
import uk.org.tfm.model.timetable.BusRoutePattern;
import uk.org.tfm.model.timetable.BusRouteShape;
import uk.org.tfm.model.timetable.RoutePoint;
import uk.org.tfm.model.timetable.SharedRoadSegment;
import uk.org.tfm.proto.v1.LatLng;
import uk.org.tfm.server.config.Config;
import uk.org.tfm.server.db.OsrmRouter;
import uk.org.tfm.server.gtfs.geometry.BundledRoute;
import uk.org.tfm.server.gtfs.geometry.EdgeKey;
import uk.org.tfm.server.gtfs.geometry.GeometryAlgoVersion;
import uk.org.tfm.server.gtfs.geometry.RouteBundler;
import uk.org.tfm.server.gtfs.geometry.Trunk;

/** Builds and publishes derived bus map geometry when its input fingerprint changes. */
@Singleton
public final class BusGeometryBuilder {
  private static final Logger log = LoggerFactory.getLogger(BusGeometryBuilder.class);
  private final BusGeometryStore store;
  private final LoadStateStore loadStates;
  private final OsrmRouter osrmRouter;
  private final String osrmBuildId;
  // Routes outside the configured extract must not be snapped onto unrelated roads.
  private final double[] coverageBbox;

  @Inject
  public BusGeometryBuilder(
      BusGeometryStore store, LoadStateStore loadStates, OsrmRouter osrmRouter, Config config) {
    this.store = store;
    this.loadStates = loadStates;
    this.osrmRouter = osrmRouter;
    this.osrmBuildId = config.osrmBuildId();
    this.coverageBbox = parseBbox(System.getenv("OSRM_COVERAGE_BBOX"));
  }

  /** Called after the timetable import (or unchanged-feed check) completes. */
  public void refresh(String feedVersion, LoadState prev) throws StorageException {
    String geometryKey = geometryKey(feedVersion, osrmBuildId);
    // ---- Geometry gate: derived geometry depends on feed + OSRM network + algorithm version.
    // ----
    if (geometryKey.equals(prev.geometryKey()) && prev.geometryBuiltAt() != null) {
      log.info(
          "Geometry inputs unchanged (feed {}, osrm '{}', algo {}) — serving existing "
              + "route/trunk geometry built {}.",
          shortHash(feedVersion),
          osrmBuildId,
          shortHash(GeometryAlgoVersion.HASH),
          prev.geometryBuiltAt());
    } else {
      log.info(
          "Rebuilding derived geometry (feed {}, osrm '{}', algo {}).",
          shortHash(feedVersion),
          osrmBuildId,
          shortHash(GeometryAlgoVersion.HASH));

      // The simplified GTFS-shape overlay (pure PostGIS from gtfs_shapes). Throws to the outer
      // catch on failure, leaving the geometry fingerprint unrecorded so it retries.
      store.rebuildPublishedShapes();

      // The two OSRM-driven builds each isolate their own failures (a long OSRM job or outage
      // must
      // not fail the whole load). If either fails we do NOT record the fingerprint, so the next
      // reload retries — matching the previous always-rebuild resilience.
      boolean geomOk = true;
      try {
        buildSnappedRoutes();
      } catch (Throwable t) {
        geomOk = false;
        log.error(
            "Road-snapping shapeless routes failed; overlay keeps its existing osrm rows.", t);
      }
      try {
        buildSharedRoutes();
      } catch (Throwable t) {
        geomOk = false;
        log.error("Building shared bus geometry failed; the overlay keeps its previous data.", t);
      }

      if (geomOk) {
        // Promote the freshly-built staging tables to live + record the fingerprint, atomically.
        loadStates.publishGeometry(geometryKey, Instant.now());
      } else {
        dropGeometryStaging();
        log.warn(
            "Geometry build had failures — staged generation discarded, fingerprint not "
                + "recorded; will retry next reload.");
      }
    }
  }

  /**
   * sha256(feedVersion | osrmBuildId | geometryAlgoVersion) — the derived geometry's freshness key.
   */
  static String geometryKey(String feedVersion, String osrmBuildId) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(
          (feedVersion + '|' + osrmBuildId + '|' + GeometryAlgoVersion.HASH)
              .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** First 8 hex chars of a fingerprint for log lines (or "none" when absent). */
  private static String shortHash(String h) {
    return h == null ? "none" : h.substring(0, Math.min(8, h.length()));
  }

  // ---- Derived route geometry (all-routes overlay) ----

  // ---- OSRM-snapped routes (operators that publish no GTFS shape) ----

  /** Politeness gap between live OSRM calls (the default target is the public demo server). */
  private static final long SNAP_GAP_MS = 150;

  /** Hard cap on live OSRM calls per cycle; the rest are picked up on the next reload. */
  private static final int MAX_SNAP_CALLS = 600;

  /**
   * Drop snapped routes longer than this (metres). Real local/regional buses top out well under it
   * (the long Arriva 5A is ~34km); anything beyond is a long-distance coach or an OSRM detour
   * blow-up from a mis-snapped stop — a giant wrong line that would smear across many viewports.
   */
  private static final long MAX_SNAPPED_ROUTE_M = 120_000;

  /**
   * Snap-algorithm version, baked into the persisted {@code osrm:<ver>:<pattern>} shape_id. Bump it
   * whenever the road-snapping output changes (e.g. v2 added car-park / access-road spur pruning)
   * so the next load discards every stored osrm shape as "no longer current" and re-snaps it once.
   * Old unversioned ids ("osrm:&lt;hash&gt;") are likewise treated as stale and replaced.
   *
   * <p>GAP (todo F1): this cache key does NOT include the OSRM network id (Config.osrmBuildId /
   * OSRM_VERSION), even though the geometry freshness key does. So rebuilding the OSRM extract and
   * bumping OSRM_VERSION re-snaps gtfs_route_trunks (they re-snap the whole network every load) but
   * the gtfs_route_shapes osrm rows here are REUSED — they keep geometry snapped against the OLD
   * network until this SNAP_VERSION is bumped by hand. Until F1 folds osrmBuildId into this key,
   * bump SNAP_VERSION whenever you rebuild OSRM (see deploy/.env.example).
   */
  private static final String SNAP_VERSION = "v2";

  /**
   * Road-snaps the stop sequence of every shapeless route (no GTFS geometry — e.g. the Arriva
   * Melton 5A, the MC minibuses) and stores the result as {@code source='osrm'} rows in {@code
   * gtfs_route_shapes}, so the overlay can draw them too.
   *
   * <p>Snapping is expensive (one OSRM call each, default target is the public demo server) so rows
   * are keyed by a stable stop-pattern hash and persisted: a route already present is only
   * refreshed (names), never re-snapped, and the work survives restarts via Postgres. Only the
   * first ever load pays the full ~hundreds-of-calls cost; reloads make near-zero OSRM calls. Calls
   * are throttled and capped; an OSRM failure simply skips that route (retried next reload).
   */
  private void buildSnappedRoutes() throws StorageException, InterruptedException {
    if (!osrmRouter.enabled()) {
      log.info("OSRM disabled (OSRM_URL blank) — skipping road-snapping of shapeless routes.");
      return;
    }
    long startMs = System.currentTimeMillis();

    List<BusRoutePattern> routes = store.shapelessRoutes();
    if (routes.isEmpty()) {
      log.info("No shapeless routes to road-snap.");
      return;
    }

    // Pre-flight: if OSRM is down, skip the whole cycle rather than grinding through hundreds of
    // doomed, politeness-paced calls (~2 min). Existing osrm rows are preserved; retried next
    // reload.
    if (!osrmRouter.isReachable()) {
      log.warn(
          "OSRM unreachable — skipping road-snapping this cycle ({} shapeless routes pending); "
              + "existing osrm rows preserved, will retry on the next reload.",
          routes.size());
      return;
    }

    Set<String> currentIds =
        routes.stream().map(r -> shapeId(r.pattern())).collect(Collectors.toSet());
    Set<String> existing = store.retainSnappedRoutes(currentIds);
    int reused = 0, snapped = 0, failed = 0, skippedOutside = 0, calls = 0;
    Set<String> done = new HashSet<>();
    for (BusRoutePattern r : routes) {
      String id = shapeId(r.pattern());
      if (!done.add(id)) {
        continue; // same pattern already handled earlier in this run
      }
      if (existing.contains(id)) {
        // Already road-snapped on a previous load — just keep the display names fresh (no
        // OSRM).
        store.updateSnappedRouteNames(id, r.shortName(), r.longName());
        reused++;
        continue;
      }
      if (calls >= MAX_SNAP_CALLS) {
        log.warn(
            "Hit OSRM call cap ({}) — {} routes deferred to the next reload.",
            MAX_SNAP_CALLS,
            routes.size() - reused - snapped - failed);
        break;
      }

      List<double[]> coords = coordinates(r.stops());
      if (outsideCoverage(coords)) {
        // Stops fall outside the OSRM extract's coverage — snapping would route them to the
        // nearest in-extract road and produce a nonsense line. Skip cleanly.
        skippedOutside++;
        continue;
      }
      List<LatLng> geom = osrmRouter.snapToRoads(coords);
      calls++;
      if (geom.size() < 2) {
        failed++;
      } else {
        if (store.saveSnappedRoute(
            new BusRouteShape(id, r.shortName(), r.longName(), points(geom)))) {
          snapped++;
        } else {
          failed++;
        }
      }
      Thread.sleep(SNAP_GAP_MS); // be polite to the (possibly public) OSRM server
    }
    int dropped = store.finishSnappedRoutes(MAX_SNAPPED_ROUTE_M);
    log.info(
        "Road-snapped routes: {} new, {} reused, {} failed, {} outside coverage, {} dropped-too-long ({} calls) in {}ms.",
        snapped,
        reused,
        failed,
        skippedOutside,
        dropped,
        calls,
        System.currentTimeMillis() - startMs);
  }

  /**
   * Parses {@code "minLat,minLng,maxLat,maxLng"} (matching the other bbox envs); null if unset/bad.
   */
  private static double[] parseBbox(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    String[] p = s.split(",");
    if (p.length != 4) {
      return null;
    }
    try {
      return new double[] {
        Double.parseDouble(p[0].trim()),
        Double.parseDouble(p[1].trim()),
        Double.parseDouble(p[2].trim()),
        Double.parseDouble(p[3].trim())
      };
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * True if any stop ({@code [lng, lat]}) falls outside the coverage bbox (false when no bbox set).
   */
  private boolean outsideCoverage(List<double[]> coords) {
    if (coverageBbox == null) {
      return false;
    }
    double minLat = coverageBbox[0],
        minLng = coverageBbox[1],
        maxLat = coverageBbox[2],
        maxLng = coverageBbox[3];
    for (double[] c : coords) {
      if (c[1] < minLat || c[1] > maxLat || c[0] < minLng || c[0] > maxLng) {
        return true;
      }
    }
    return false;
  }

  /** Parses {@code "lng,lat;lng,lat;..."} into a list of {@code [lng, lat]} pairs. */
  /**
   * Reduces a round-trip snapped path to a single direction: keeps {@code [0 .. apex]}, where the
   * apex is the vertex farthest (great-circle) from the start. Only cuts a clear out-and-back — the
   * path must return toward its start ({@code dEnd < }{@value #DEDIR_RETURN_FRAC}{@code · dApex})
   * AND travel much farther than its straight reach ({@code pathLen > }{@value
   * #DEDIR_DOUBLEBACK}{@code · dApex}), with the turnaround well inside the line. A clean one-way
   * line's end is its farthest point, so it never matches and is returned unchanged. Geometry and
   * node ids stay aligned 1:1.
   */
  static OsrmRouter.SnappedPath cutOutAndBack(OsrmRouter.SnappedPath sp) {
    List<LatLng> g = sp.geometry();
    long[] ns = sp.nodes();
    int n = g.size();
    if (n < 4) {
      return sp;
    }
    int apex = 0;
    double dApex = 0;
    double pathLen = 0;
    for (int i = 0; i < n; i++) {
      double dStart = haversineM(g.get(0), g.get(i));
      if (dStart > dApex) {
        dApex = dStart;
        apex = i;
      }
      if (i > 0) {
        pathLen += haversineM(g.get(i - 1), g.get(i));
      }
    }
    double dEnd = haversineM(g.get(0), g.get(n - 1));
    boolean outAndBack =
        dApex > 0
            && dEnd < DEDIR_RETURN_FRAC * dApex
            && pathLen > DEDIR_DOUBLEBACK * dApex
            && apex > n * 0.1
            && apex < n * 0.9;
    if (!outAndBack) {
      return sp;
    }
    return new OsrmRouter.SnappedPath(
        List.copyOf(g.subList(0, apex + 1)), Arrays.copyOf(ns, apex + 1));
  }

  /** Great-circle distance in metres between two points (mirrors OsrmRouter.haversine). */
  private static double haversineM(LatLng a, LatLng b) {
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

  // ---- Shared road segments for the bus network overlay ----

  /**
   * Cap on points sent to OSRM /match (its default {@code --max-matching-size} is 100). Longer
   * simplified shapes are decimated to this; pathological ones fail match → fall back to stop snap.
   */
  private static final int MAX_MATCH_POINTS = 95;

  // De-direction: many operators publish a single trip's shape as a full ROUND TRIP (out to the end
  // of the line and back), so the drawn route doubles back on itself and loops at one-way pairs /
  // terminus turnarounds. We keep only the outbound leg (vertex 0 .. the farthest-from-start
  // "apex")
  // when the path clearly goes out and comes back. A clean one-way line's end IS its farthest
  // point,
  // so it never matches and is left untouched. Complements OsrmRouter.pruneSpurs (which peels the
  // SHORT car-park spurs); this peels the WHOLE-route doubleback.
  /** Cut only if the path returns this close (fraction of its farthest reach) toward its start. */
  private static final double DEDIR_RETURN_FRAC = 0.5;

  /**
   * ...and travels at least this multiple of its straight-line reach (i.e. it really doubles back).
   */
  private static final double DEDIR_DOUBLEBACK = 1.6;

  /** Builds shared road segments and route membership from node-snapped routes. */
  private void buildSharedRoutes() throws StorageException {
    if (!osrmRouter.enabled() || coverageBbox == null) {
      log.info("Shared bus geometry skipped (OSRM disabled or no OSRM_COVERAGE_BBOX).");
      return;
    }
    long startMs = System.currentTimeMillis();

    // 1. Build each in-coverage route's node-annotated geometry. Prefer the operator's published
    // shape
    //    (map-matched to OSM nodes) so the overlay follows the real road — including legs no stop
    // pins,
    //    which stop-to-stop snapping would route around. Fall back to stop snapping for shapeless
    //    routes, or when matching fails (OSRM split the trace / too long).
    List<BusRoutePattern> candidates =
        store.routesForBundling(coverageBbox[0], coverageBbox[1], coverageBbox[2], coverageBbox[3]);
    List<BundledRoute> routes = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int failed = 0;
    int skippedOutside = 0;
    int matchedShape = 0;
    int snappedStops = 0;
    int deDirected = 0;
    for (BusRoutePattern r : candidates) {
      if (!seen.add(shapeId(r.pattern()))) {
        continue;
      }
      List<double[]> stops = coordinates(r.stops());
      if (outsideCoverage(stops)) {
        skippedOutside++;
        continue;
      }
      OsrmRouter.SnappedPath sp = null;
      List<double[]> shapeTrace = coordinates(r.shape());
      if (shapeTrace.size() >= 2) {
        OsrmRouter.SnappedPath m =
            osrmRouter.matchWithNodes(capPoints(shapeTrace, MAX_MATCH_POINTS));
        if (m.geometry().size() >= 2) {
          sp = m;
          matchedShape++;
        }
      }
      if (sp == null) {
        OsrmRouter.SnappedPath s = osrmRouter.snapWithNodes(stops);
        if (s.geometry().size() >= 2) {
          sp = s;
          snappedStops++;
        }
      }
      if (sp == null) {
        failed++;
        continue;
      }
      // Reduce a published round-trip shape to its outbound leg, so the route doesn't double back
      // on
      // itself (and its return-leg edges don't form one-way-pair / terminus loops in the trunks).
      OsrmRouter.SnappedPath oneWay = cutOutAndBack(sp);
      if (oneWay != sp) {
        deDirected++;
      }
      routes.add(
          new BundledRoute(
              shapeId(r.pattern()),
              r.shortName(),
              r.longName(),
              oneWay.geometry(),
              oneWay.nodes()));
    }

    // 2. Shared-edge graph: OSM edge -> distinct route NAMES traversing it. Keyed by short name
    // (not
    //    per-variant index) so a route's major variants — and same-named route_ids — share one
    // identity on
    //    common streets (they overlay exactly) without duplicate membership.
    Map<EdgeKey, Set<String>> edgeRoutes = RouteBundler.buildEdgeRoutes(routes);

    buildTrunks(routes, edgeRoutes);
    log.info(
        "Built shared bus geometry: {} routes ({} matched-shape, {} stop-snapped, "
            + "{} de-directed), {} failed, {} skipped-outside in {}ms.",
        routes.size(),
        matchedShape,
        snappedStops,
        deDirected,
        failed,
        skippedOutside,
        System.currentTimeMillis() - startMs);
  }

  /**
   * Persists {@code gtfs_route_trunks} from the pure trunk geometry computed by {@link
   * RouteBundler#buildTrunks}. The algorithm collapses the per-route lines into shared "trunks" —
   * maximal chains of OSM edges traversed by the SAME set of routes; here we only decorate each
   * trunk's consistently ordered route names with their long names + operators and write the rows.
   */
  private void buildTrunks(List<BundledRoute> routes, Map<EdgeKey, Set<String>> edgeRoutes)
      throws StorageException {
    long startMs = System.currentTimeMillis();

    List<Trunk> trunkList = RouteBundler.buildTrunks(routes, edgeRoutes);

    Map<String, String> operatorByRoute = store.routeOperators();
    Map<String, String> gtfsColorByRoute = store.routeColors();

    // Display fields per route NAME (edges are name-keyed, so a name appears once per trunk no
    // matter how many variants/route_ids share it): first variant in load order wins.
    Map<String, BundledRoute> repByName = new HashMap<>();
    for (BundledRoute r : routes) {
      repByName.putIfAbsent(r.shortName(), r);
    }

    List<SharedRoadSegment> segments = new ArrayList<>();
    for (Trunk trunk : trunkList) {
      List<BusRouteLabel> labels = new ArrayList<>();
      for (String name : trunk.routeShortNames()) {
        BundledRoute route = repByName.get(name);
        String key = route.shortName() + "|" + route.longName();
        labels.add(
            new BusRouteLabel(
                route.shortName(),
                route.longName(),
                operatorByRoute.getOrDefault(key, ""),
                RouteColor.forRoute(route.shortName(), gtfsColorByRoute.getOrDefault(key, ""))));
      }
      segments.add(new SharedRoadSegment(points(trunk.geom()), labels));
    }
    store.stageTrunks(segments, MAX_SNAPPED_ROUTE_M);
    log.info(
        "Built shared bus geometry (staged): {} trunks in {}ms.",
        segments.size(),
        System.currentTimeMillis() - startMs);
  }

  /**
   * Discards a partial/failed geometry generation so the next reload rebuilds from clean staging.
   */
  private void dropGeometryStaging() {
    try {
      store.discardStagedTrunks();
    } catch (StorageException e) {
      log.warn("Failed to discard staged bus geometry; next refresh will recreate it.", e);
    }
  }

  private static String shapeId(String pattern) {
    return "osrm:" + SNAP_VERSION + ":" + pattern;
  }

  private static List<double[]> coordinates(List<RoutePoint> points) {
    return points.stream().map(p -> new double[] {p.lng(), p.lat()}).toList();
  }

  private static List<RoutePoint> points(List<LatLng> points) {
    return points.stream().map(p -> new RoutePoint(p.getLat(), p.getLng())).toList();
  }

  /**
   * Evenly downsamples a trace to at most {@code max} points, always keeping the first and last.
   */
  private static List<double[]> capPoints(List<double[]> pts, int max) {
    if (pts.size() <= max) {
      return pts;
    }
    List<double[]> out = new ArrayList<>(max);
    double step = (pts.size() - 1) / (double) (max - 1);
    for (int i = 0; i < max; i++) {
      out.add(pts.get((int) Math.round(i * step)));
    }
    return out;
  }
}
