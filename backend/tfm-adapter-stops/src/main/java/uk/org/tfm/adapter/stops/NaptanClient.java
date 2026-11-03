package uk.org.tfm.adapter.stops;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.catalog.CatalogProvider;
import uk.org.tfm.model.catalog.StopDefinition;
import uk.org.tfm.model.feed.FeedException;

/** Downloads and parses the NaPTAN stop catalog, with a daily source cache. */
public final class NaptanClient implements CatalogProvider<StopDefinition> {

  private static final Logger log = LoggerFactory.getLogger(NaptanClient.class);

  private static final String NAPTAN_BASE_URL =
      "https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=csv";
  private static final String CSV_NAME = "naptan.csv";
  private static final Duration CACHE_MAX_AGE = Duration.ofHours(24);

  /**
   * GB bounding box used to reject rows with placeholder / out-of-range coordinates. NaPTAN has
   * some rows at (49.76, -7.55) which are known bad data.
   */
  private static final double GB_MIN_LAT = 49.0;

  private static final double GB_MAX_LAT = 61.0;
  private static final double GB_MIN_LNG = -8.7;
  private static final double GB_MAX_LNG = 1.9;

  private static final String[] KEPT_STOP_TYPES = {"BCT", "BCS", "BCQ", "RSE", "RLY", "MET"};

  private final Path cacheDir;
  private final String naptanAreas; // blank = national
  private final HttpClient http;

  public NaptanClient(Path cacheDir, String naptanAreas) {
    this.cacheDir = cacheDir;
    this.naptanAreas = naptanAreas;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  // ---- Source cache timestamp ----

  /**
   * The last-modified time of the cached NaPTAN CSV ({@code naptan.csv}) in the configured cache
   * directory, or empty if the file does not exist or cannot be stat'd. Never throws.
   */
  public Optional<Instant> lastLoadedAt() {
    try {
      Path csvPath = cacheDir.resolve(CSV_NAME);
      if (!Files.exists(csvPath)) {
        return Optional.empty();
      }
      return Optional.of(Files.getLastModifiedTime(csvPath).toInstant());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  @Override
  public List<StopDefinition> fetch() throws FeedException {
    Path csvPath = cacheDir.resolve(CSV_NAME);
    try {
      ensureCached(csvPath);
    } catch (IOException e) {
      throw new FeedException("Catalog download/cache failed", FeedException.Kind.UNAVAILABLE, e);
    }
    try {
      return parseCsv(csvPath);
    } catch (IOException e) {
      throw new FeedException("Catalog CSV parse failed", FeedException.Kind.INVALID_RESPONSE, e);
    }
  }

  // ---- Download ----

  private void ensureCached(Path csvPath) throws IOException {
    if (Files.exists(csvPath)) {
      Instant lastModified = Files.getLastModifiedTime(csvPath).toInstant();
      if (Duration.between(lastModified, Instant.now()).compareTo(CACHE_MAX_AGE) < 0) {
        log.info("NaPTAN cache is fresh ({}), skipping download.", csvPath);
        return;
      }
      log.info("NaPTAN cache is stale (>{}h), re-downloading.", CACHE_MAX_AGE.toHours());
    } else {
      log.info("NaPTAN CSV not cached at {}, downloading.", csvPath);
    }
    Files.createDirectories(cacheDir);
    download(csvPath);
  }

  private void download(Path dest) throws IOException {
    String url = buildUrl();
    Path tmp = dest.resolveSibling(CSV_NAME + ".tmp");

    log.info("Downloading NaPTAN CSV from {} ...", url);
    long startMs = System.currentTimeMillis();

    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(15)) // national CSV is ~440k rows; allow time on slow links
            .GET()
            .build();

    HttpResponse<Path> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("NaPTAN download interrupted", e);
    } catch (Exception e) {
      throw new IOException("NaPTAN download failed: " + e.getClass().getSimpleName(), e);
    }

    if (resp.statusCode() != 200) {
      Files.deleteIfExists(tmp);
      throw new IOException("NaPTAN download returned HTTP " + resp.statusCode());
    }

    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    long elapsedMs = System.currentTimeMillis() - startMs;
    long sizeKb = Files.size(dest) / 1024;
    log.info("NaPTAN CSV downloaded: {}KB in {}ms -> {}", sizeKb, elapsedMs, dest);
  }

  private String buildUrl() {
    if (naptanAreas.isBlank()) {
      return NAPTAN_BASE_URL;
    }
    // naptanAreas is comma-separated; the API accepts atcoAreaCodes as a single query param
    // with comma-separated values.
    return NAPTAN_BASE_URL + "&atcoAreaCodes=" + naptanAreas.trim();
  }

  // ---- Parse ----

  /**
   * Parses the NaPTAN CSV. The file has a header row; columns are double-quoted, comma-separated.
   * We parse by header name (not position) for robustness.
   *
   * <p>Filtering rules applied during parse (not post-hoc, to minimise memory):
   *
   * <ul>
   *   <li>Status == 'active' only.
   *   <li>StopType in {BCT, BCS, BCQ, RSE, RLY, MET} only.
   *   <li>Lat/lng must be parseable doubles and fall within the GB bbox.
   * </ul>
   */
  static List<StopDefinition> parseCsv(Path csvPath) throws IOException {
    List<StopDefinition> out = new ArrayList<>(150_000);

    try (BufferedReader br =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(csvPath), StandardCharsets.UTF_8),
            256 * 1024)) {

      String headerLine = br.readLine();
      if (headerLine == null) {
        throw new IOException("NaPTAN CSV is empty");
      }

      // Locate required column indices by name, stripping BOM if present.
      int[] idx =
          findIndices(
              headerLine,
              "ATCOCode",
              "CommonName",
              "Indicator",
              "LocalityName",
              "ParentLocalityName",
              "Latitude",
              "Longitude",
              "StopType",
              "Status");
      int iAtco = idx[0];
      int iName = idx[1];
      int iIndicator = idx[2];
      int iLocality = idx[3];
      int iParent = idx[4];
      int iLat = idx[5];
      int iLng = idx[6];
      int iStopType = idx[7];
      int iStatus = idx[8];

      String line;
      while ((line = br.readLine()) != null) {
        if (line.isBlank()) continue;
        String[] fields = parseCsvLine(line);

        int maxNeeded =
            maxOf(iAtco, iName, iIndicator, iLocality, iParent, iLat, iLng, iStopType, iStatus);
        if (fields.length <= maxNeeded) continue;

        // Status filter
        if (!"active".equalsIgnoreCase(fields[iStatus].trim())) continue;

        // StopType filter
        String stopType = fields[iStopType].trim();
        if (!isKeptStopType(stopType)) continue;

        // Lat/lng parse and GB bbox
        double lat;
        double lng;
        try {
          lat = Double.parseDouble(fields[iLat].trim());
          lng = Double.parseDouble(fields[iLng].trim());
        } catch (NumberFormatException e) {
          continue;
        }
        if (!inGbBbox(lat, lng)) continue;

        String atcoCode = fields[iAtco].trim();
        String name = fields[iName].trim();
        if (atcoCode.isEmpty() || name.isEmpty()) continue;

        // Resolve a National Rail CRS for rail stops (RLY by TIPLOC, RSE by name/proximity); ""
        // otherwise.
        String crs = RAIL_REFS.resolve(stopType, atcoCode, name, lat, lng);

        out.add(
            new StopDefinition(
                atcoCode,
                name,
                fields[iIndicator].trim(),
                fields[iLocality].trim(),
                fields[iParent].trim(),
                lat,
                lng,
                stopType,
                crs));
      }
    }
    return out;
  }

  private static boolean isKeptStopType(String stopType) {
    for (String t : KEPT_STOP_TYPES) {
      if (t.equals(stopType)) return true;
    }
    return false;
  }

  private static boolean inGbBbox(double lat, double lng) {
    return lat >= GB_MIN_LAT && lat <= GB_MAX_LAT && lng >= GB_MIN_LNG && lng <= GB_MAX_LNG;
  }

  // ---- CSV helpers (header-name based) ----

  /**
   * Finds column indices for the given names in a CSV header line. The NaPTAN header is
   * double-quoted; we unquote each column name before matching. Throws {@link IOException} if any
   * name is absent.
   */
  private static int[] findIndices(String headerLine, String... names) throws IOException {
    // Split the header on commas, unquote each column name.
    String[] cols = parseCsvLine(headerLine);
    for (int i = 0; i < cols.length; i++) {
      cols[i] = cols[i].trim().replace("﻿", ""); // strip BOM
    }

    int[] out = new int[names.length];
    for (int n = 0; n < names.length; n++) {
      out[n] = -1;
      for (int c = 0; c < cols.length; c++) {
        if (names[n].equals(cols[c])) {
          out[n] = c;
          break;
        }
      }
      if (out[n] < 0) {
        throw new IOException(
            "NaPTAN CSV header missing column '" + names[n] + "'; found: " + headerLine);
      }
    }
    return out;
  }

  /**
   * Quote-aware CSV line splitter. Handles double-quoted fields (with embedded commas and {@code
   * ""} escape sequences). Mirrors the implementation in {@code GtfsLoader} which is well-tested.
   */
  static String[] parseCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    int len = line.length();
    int i = 0;
    while (i <= len) {
      if (i == len) {
        fields.add("");
        break;
      }
      if (line.charAt(i) == '"') {
        StringBuilder sb = new StringBuilder();
        i++; // skip opening quote
        while (i < len) {
          char c = line.charAt(i);
          if (c == '"') {
            if (i + 1 < len && line.charAt(i + 1) == '"') {
              sb.append('"');
              i += 2;
            } else {
              i++; // skip closing quote
              break;
            }
          } else {
            sb.append(c);
            i++;
          }
        }
        fields.add(sb.toString());
        if (i < len && line.charAt(i) == ',') i++;
      } else {
        int start = i;
        while (i < len && line.charAt(i) != ',') i++;
        fields.add(line.substring(start, i));
        if (i < len) i++;
      }
    }
    return fields.toArray(new String[0]);
  }

  private static int maxOf(int... values) {
    int max = 0;
    for (int v : values) if (v > max) max = v;
    return max;
  }

  // ---- Rail CRS resolution ----

  /** Loaded once at class init from the bundled NaPTAN rail reference. */
  private static final RailRefResolver RAIL_REFS = RailRefResolver.load();

  /**
   * Resolves a National Rail CRS for rail stops from the bundled {@code rail-references.csv}
   * (NaPTAN AnnotatedRailRef: TIPLOC, CRS, station name, WGS84 coords for ~2,670 GB stations).
   *
   * <ul>
   *   <li><b>RLY</b> stops use ATCO {@code 9100<TIPLOC>} → exact TIPLOC lookup.
   *   <li><b>RSE</b> (station entrances) lack a TIPLOC in their ATCO → exact case-insensitive
   *       station-name match, falling back to the nearest reference station within {@value
   *       #MAX_PROXIMITY_M} m.
   *   <li>Bus/metro and any rail stop that resolves to nothing get {@code ""}.
   * </ul>
   */
  static final class RailRefResolver {

    /** Hard cap on the RSE proximity fallback so an entrance never snaps to a different station. */
    private static final double MAX_PROXIMITY_M = 500.0;

    private final Map<String, String> crsByTiploc;
    private final Map<String, String> crsByName; // key: lower-cased station name
    private final double[] lat;
    private final double[] lng;
    private final String[] crs;

    private RailRefResolver(
        Map<String, String> crsByTiploc,
        Map<String, String> crsByName,
        double[] lat,
        double[] lng,
        String[] crs) {
      this.crsByTiploc = crsByTiploc;
      this.crsByName = crsByName;
      this.lat = lat;
      this.lng = lng;
      this.crs = crs;
    }

    String resolve(String stopType, String atcoCode, String name, double stopLat, double stopLng) {
      if ("RLY".equals(stopType)) {
        if (atcoCode.startsWith("9100")) {
          String hit = crsByTiploc.get(atcoCode.substring(4).toUpperCase());
          if (hit != null) return hit;
        }
        // Heritage/closed RLY lines aren't in the national reference → no board.
        return "";
      }
      if ("RSE".equals(stopType)) {
        String hit = crsByName.get(name.toLowerCase());
        if (hit != null) return hit;
        return nearest(stopLat, stopLng);
      }
      return ""; // BCT/BCS/BCQ/MET — no National Rail board
    }

    /** CRS of the nearest reference station within {@link #MAX_PROXIMITY_M}, else "". */
    private String nearest(double stopLat, double stopLng) {
      double bestM = MAX_PROXIMITY_M;
      String best = "";
      for (int i = 0; i < crs.length; i++) {
        double d = haversineMetres(stopLat, stopLng, lat[i], lng[i]);
        if (d < bestM) {
          bestM = d;
          best = crs[i];
        }
      }
      return best;
    }

    private static double haversineMetres(double lat1, double lng1, double lat2, double lng2) {
      double r = 6_371_000.0;
      double dLat = Math.toRadians(lat2 - lat1);
      double dLng = Math.toRadians(lng2 - lng1);
      double a =
          Math.sin(dLat / 2) * Math.sin(dLat / 2)
              + Math.cos(Math.toRadians(lat1))
                  * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLng / 2)
                  * Math.sin(dLng / 2);
      return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Parses {@code /naptan/rail-references.csv} from the classpath. Never throws — a missing or
     * malformed resource yields an empty resolver (every rail stop then gets "" → bus board, the
     * pre-feature behaviour) rather than failing the whole NaPTAN load.
     */
    static RailRefResolver load() {
      Map<String, String> byTiploc = new HashMap<>(4096);
      Map<String, String> byName = new HashMap<>(4096);
      List<double[]> coords = new ArrayList<>(4096);
      List<String> crsList = new ArrayList<>(4096);
      try (InputStream in = NaptanClient.class.getResourceAsStream("/naptan/rail-references.csv")) {
        if (in == null) {
          log.warn(
              "rail-references.csv not on classpath — rail stops will have no CRS (bus board fallback)");
          return new RailRefResolver(byTiploc, byName, new double[0], new double[0], new String[0]);
        }
        try (BufferedReader br =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
          String line;
          boolean header = false;
          while ((line = br.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) continue;
            if (!header) {
              header = true;
              continue;
            } // skip the column header row
            String[] f = parseCsvLine(line);
            if (f.length < 5) continue;
            String crsCode = f[0].trim();
            String tiploc = f[1].trim().toUpperCase();
            String name = f[2].trim();
            if (crsCode.isEmpty()) continue;
            if (!tiploc.isEmpty()) byTiploc.put(tiploc, crsCode);
            if (!name.isEmpty()) byName.putIfAbsent(name.toLowerCase(), crsCode);
            try {
              coords.add(
                  new double[] {Double.parseDouble(f[3].trim()), Double.parseDouble(f[4].trim())});
              crsList.add(crsCode);
            } catch (NumberFormatException ignore) {
              // skip the proximity entry for a row with unparseable coords; name/tiploc still
              // usable
            }
          }
        }
      } catch (IOException e) {
        log.warn("Failed to read rail-references.csv — rail stops will have no CRS", e);
        return new RailRefResolver(byTiploc, byName, new double[0], new double[0], new String[0]);
      }
      double[] lat = new double[coords.size()];
      double[] lng = new double[coords.size()];
      for (int i = 0; i < coords.size(); i++) {
        lat[i] = coords.get(i)[0];
        lng[i] = coords.get(i)[1];
      }
      log.info(
          "Loaded {} rail CRS references ({} by TIPLOC, {} by name)",
          crsList.size(),
          byTiploc.size(),
          byName.size());
      return new RailRefResolver(byTiploc, byName, lat, lng, crsList.toArray(new String[0]));
    }
  }
}
