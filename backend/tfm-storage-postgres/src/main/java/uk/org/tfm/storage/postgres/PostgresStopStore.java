package uk.org.tfm.storage.postgres;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.catalog.NearbyStop;
import uk.org.tfm.model.catalog.NearbyStopPage;
import uk.org.tfm.model.catalog.RankedStop;
import uk.org.tfm.model.catalog.Stop;
import uk.org.tfm.model.catalog.StopDefinition;
import uk.org.tfm.model.catalog.StopPage;
import uk.org.tfm.model.storage.StopStore;
import uk.org.tfm.model.storage.StorageException;

/**
 * Direct-JDBC DAO for the {@code stops} table (NaPTAN, PostGIS-backed).
 *
 * <p>Three operations:
 *
 * <ol>
 *   <li>{@link #inViewport} — spatial bounding-box query using the partial GiST index.
 *   <li>{@link #search} — trigram autocomplete using the GIN index on {@code search_text}.
 *   <li>{@link #bulkLoad} — atomic TRUNCATE + COPY via PostgreSQL CopyManager.
 * </ol>
 */
public final class PostgresStopStore implements StopStore {

  private static final Logger log = LoggerFactory.getLogger(PostgresStopStore.class);

  /** Never return more than this many stops for a viewport query. */
  static final int VIEWPORT_CAP = 500;

  /** Fetch one extra to detect truncation without a COUNT(*). */
  private static final int VIEWPORT_FETCH = VIEWPORT_CAP + 1;

  /**
   * Geographic proximity bias constants for stop search ranking.
   *
   * <p>Melton Mowbray town centre (lng -0.88, lat 52.77) is used as the home point. Note:
   * ST_MakePoint takes (lng, lat) order.
   *
   * <p>The penalty is {@code MELTON_BIAS_WEIGHT * LEAST(distance_m / MELTON_BIAS_SCALE_M, 1.0)},
   * subtracting up to {@value #MELTON_BIAS_WEIGHT} from the word-similarity score for stops at or
   * beyond {@value #MELTON_BIAS_SCALE_M} metres away. This means two stops with near-identical name
   * scores sort toward Melton (Casterton Road in Melton beats Great Casterton 27 km away; Asfordby
   * beats Margate), while a materially better name match elsewhere still wins.
   *
   * <p>The scale is deliberately tight (30 km — roughly the Melton travel-to-work area) so anything
   * in another town/county takes close to the full penalty.
   */
  private static final double MELTON_LNG = -0.88;

  private static final double MELTON_LAT = 52.77;
  private static final double MELTON_BIAS_WEIGHT = 0.2;
  private static final double MELTON_BIAS_SCALE_M = 30_000.0;

  /**
   * Hard catchment radius (metres) for search: only stops within this distance of Melton are
   * returned. The proximity <em>bias</em> above only nudges ranking, and at weight 0.2 it can't
   * stop a strongly name-matched national-rail station on the far side of the country (e.g. "King's
   * Cross" for "sage cross", "Windsor &amp; Eton" for "Windsor Street") from outranking the local
   * bus stop a user actually wants. This is "Transport for Melton", so we scope search to the
   * genuine local network — ~45 km comfortably covers the area the buses/trains actually serve
   * (Leicester, Oakham, Grantham, Loughborough, Nottingham, Corby) while excluding the rest of the
   * UK.
   */
  private static final double SEARCH_RADIUS_M = 45_000.0;

  /**
   * Word-similarity match floor for the search WHERE clause, lowered from pg_trgm's 0.6 default.
   * 0.5 lets a near-miss typo ("caserton" → word_similarity 0.58 against "casterton road …") still
   * match; the proximity bias + word-similarity ordering then keep the results sensible. Applied
   * per-transaction via {@code SET LOCAL} so the pooled connection's default is never mutated.
   */
  private static final double WORD_SIM_THRESHOLD = 0.5;

  private final DataSource dataSource;

  public PostgresStopStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  // ---- Viewport query ----

  /**
   * Returns up to {@value #VIEWPORT_CAP} active bus/rail stops whose location falls within the
   * given bounding box, ordered by {@code stop_rank DESC} (rail before bus). The {@code &&}
   * operator against the partial GiST indexes is the intended access path.
   *
   * @return a pair: the stop list (at most 500 elements) and a truncated flag
   */
  private StopPage queryViewport(double minLat, double minLng, double maxLat, double maxLng)
      throws SQLException {

    // PostGIS: ST_MakeEnvelope(minLng, minLat, maxLng, maxLat, srid)
    String sql =
        """
        SELECT atco_code, common_name, indicator, locality_name, stop_type, crs,
               ST_Y(location::geometry) AS lat,
               ST_X(location::geometry) AS lng
        FROM stops
        WHERE status = 'active'
          AND stop_type IN ('BCT', 'BCS', 'BCQ', 'RSE', 'RLY', 'MET')
          AND location && ST_MakeEnvelope(?, ?, ?, ?, 4326)::geography
        ORDER BY stop_rank DESC
        LIMIT ?
        """;

    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {

      ps.setDouble(1, minLng);
      ps.setDouble(2, minLat);
      ps.setDouble(3, maxLng);
      ps.setDouble(4, maxLat);
      ps.setInt(5, VIEWPORT_FETCH);

      List<Stop> stops = new ArrayList<>(VIEWPORT_CAP);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          stops.add(mapRow(rs));
        }
      }

      boolean truncated = stops.size() > VIEWPORT_CAP;
      if (truncated) {
        stops = stops.subList(0, VIEWPORT_CAP);
      }
      return new StopPage(stops, truncated);
    }
  }

  @Override
  public NearbyStopPage nearby(double lat, double lng, double radiusM, int limit, boolean busOnly)
      throws StorageException {
    String sql =
        """
        SELECT atco_code, common_name, indicator, locality_name, stop_type, crs,
               ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lng,
               ST_Distance(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_m
        FROM stops
        WHERE status = 'active'
          AND stop_type IN ('BCT', 'BCS', 'BCQ', 'RSE', 'RLY', 'MET')
          AND (NOT ? OR stop_type IN ('BCT', 'BCS', 'BCQ'))
          AND ST_DWithin(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
        ORDER BY distance_m, atco_code
        LIMIT ?
        """;
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setDouble(1, lng);
      ps.setDouble(2, lat);
      ps.setBoolean(3, busOnly);
      ps.setDouble(4, lng);
      ps.setDouble(5, lat);
      ps.setDouble(6, radiusM);
      ps.setInt(7, limit + 1);
      var rows = new ArrayList<NearbyStop>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) rows.add(new NearbyStop(mapRow(rs), rs.getDouble("distance_m")));
      }
      return new NearbyStopPage(
          List.copyOf(rows.subList(0, Math.min(limit, rows.size()))), rows.size() > limit);
    } catch (SQLException e) {
      throw new StorageException("Nearby stop query failed", e);
    }
  }

  // ---- Trigram search ----

  /**
   * Word-similarity autocomplete over {@code search_text} (lower-cased name + locality + parent
   * locality). Returns an empty list if {@code query} is shorter than 2 characters.
   *
   * <p>Uses {@code word_similarity(query, search_text)} (operator {@code %>}) rather than
   * whole-string {@code similarity}: the latter is diluted by the extra words in a multi-word
   * {@code search_text} (e.g. "casterton road melton mowbray" scores only 0.24 against "casterton",
   * losing to a shorter "great casterton" string 27 km away). word_similarity matches the query
   * against the best contiguous word-extent instead, so a clean token hit scores ~1.0 regardless of
   * the surrounding words — leaving the proximity bias free to break the tie toward Melton.
   *
   * <p><b>Rail-first ordering:</b> results are tiered — National Rail ({@code RLY}/{@code RSE})
   * first, then heritage/metro/tram ({@code MET}), then everything else (bus, by {@code stop_rank})
   * — before the word-similarity/proximity score. Because the query word saturates {@code
   * word_similarity} at ~1.0 for every stop merely <em>containing</em> a town name (the station,
   * every "Loughborough Road" bus stop, and bus stops whose locality is that town), tiering by rank
   * is what surfaces "<i>town</i> → its station" rather than a minor same-named bus stop. Within
   * each tier the word-similarity − proximity score still applies.
   *
   * @param query raw user input (will be lower-cased and trimmed server-side)
   * @param limit maximum rows; capped at {@value #SEARCH_MAX_LIMIT}, default {@value
   *     #SEARCH_DEFAULT_LIMIT}
   */
  private List<RankedStop> querySearch(String query, int limit) throws SQLException {
    String q = query.trim().toLowerCase();
    if (q.length() < 2) {
      return List.of();
    }
    int cap = (limit <= 0) ? SEARCH_DEFAULT_LIMIT : Math.min(limit, SEARCH_MAX_LIMIT);

    // Proximity bias: subtract up to MELTON_BIAS_WEIGHT from the word-similarity score by distance
    // from Melton Mowbray, capped at MELTON_BIAS_SCALE_M. Two gotchas baked into the shape below:
    //  1. The constants are compile-time literals concatenated straight in — NOT via
    // String.formatted(),
    //     which would choke on the match operator `%>` (it reads `%>` as a format conversion).
    //  2. The ORDER BY repeats the full word_similarity() expression rather than the SELECT alias
    //     `score`: Postgres only resolves an output alias when it IS the whole sort key; inside an
    //     expression (`score - …`) the identifier is treated as an input column and errors ("column
    //     score does not exist"). Hence the third `?` bind (the query, again) before the LIMIT.
    // `search_text %> ?` is GIN-index-accelerated (stops_search_trgm), so this stays an index scan.
    String sql =
        "SELECT atco_code, common_name, indicator, locality_name, stop_type, crs, "
            + "       ST_Y(location::geometry) AS lat, "
            + "       ST_X(location::geometry) AS lng, "
            + "       stop_rank, "
            // Proximity-adjusted name score (ws − Melton penalty), the same expression the ORDER BY
            // uses.
            // Surfaced as a column so the service can interleave stops with place results by one
            // unified key.
            + "       word_similarity(?, search_text) - "
            + MELTON_BIAS_WEIGHT
            + " * LEAST( "
            + "           ST_Distance(location, ST_SetSRID(ST_MakePoint("
            + MELTON_LNG
            + ", "
            + MELTON_LAT
            + "), 4326)::geography) / "
            + MELTON_BIAS_SCALE_M
            + ", "
            + "           1.0 "
            + "         ) AS score "
            + "FROM stops "
            + "WHERE status = 'active' "
            + "  AND search_text %> ? "
            // Catchment scope: drop anything outside the local network so distant national-rail
            // stations
            // can't swamp local bus stops. ST_DWithin(geography) uses the GiST index on location.
            + "  AND ST_DWithin(location, ST_SetSRID(ST_MakePoint("
            + MELTON_LNG
            + ", "
            + MELTON_LAT
            + "), 4326)::geography, "
            + SEARCH_RADIUS_M
            + ") "
            // Rail-first tiers, BEFORE the similarity/proximity score: National Rail (RLY/RSE — the
            // ones with
            // live boards & positions) above heritage/metro/tram (MET) above everything else;
            // stop_rank then
            // breaks any remaining ties. So a matching station outranks same-named bus stops, and
            // the mainline
            // station beats a heritage line (e.g. "Loughborough (Leics) Rail Station" before
            // "Loughborough
            // Central (Great Central Railway)"). Within a tier, the word-similarity − proximity
            // score applies.
            + "ORDER BY CASE WHEN stop_type IN ('RLY','RSE') THEN 2 WHEN stop_type = 'MET' THEN 1 ELSE 0 END DESC, "
            + "         stop_rank DESC, "
            + "         word_similarity(?, search_text) - "
            + MELTON_BIAS_WEIGHT
            + " * LEAST( "
            + "             ST_Distance(location, ST_SetSRID(ST_MakePoint("
            + MELTON_LNG
            + ", "
            + MELTON_LAT
            + "), 4326)::geography) / "
            + MELTON_BIAS_SCALE_M
            + ", "
            + "             1.0 "
            + "           ) DESC "
            + "LIMIT ?";

    // SET LOCAL needs a transaction; word_similarity_threshold governs the `%>` match floor.
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try {
        try (Statement st = conn.createStatement()) {
          st.execute("SET LOCAL pg_trgm.word_similarity_threshold = " + WORD_SIM_THRESHOLD);
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
          ps.setString(1, q);
          ps.setString(2, q);
          ps.setString(3, q);
          ps.setInt(4, cap);

          List<RankedStop> results = new ArrayList<>(cap);
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              Stop stop = mapRow(rs);
              results.add(
                  new RankedStop(
                      stop,
                      searchTier(stop.stopType()),
                      rs.getInt("stop_rank"),
                      rs.getDouble("score")));
            }
          }
          conn.commit();
          return results;
        }
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(true);
      }
    }
  }

  /**
   * Rail-first tier for the unified sort: National Rail (RLY/RSE) &gt; metro/heritage (MET) &gt;
   * bus.
   */
  private static int searchTier(String stopType) {
    return switch (stopType) {
      case "RLY", "RSE" -> 2;
      case "MET" -> 1;
      default -> 0;
    };
  }

  // ---- Bulk load ----

  /**
   * Atomically replaces the contents of {@code stops} with {@code rows}. Strategy:
   *
   * <ol>
   *   <li>Create (if absent) a {@code stops_staging} table and TRUNCATE it.
   *   <li>COPY the rows into staging via PostgreSQL {@link CopyManager} (binary-safe CSV).
   *   <li>In a single transaction: TRUNCATE {@code stops}, INSERT from staging with ST_MakePoint,
   *       TRUNCATE staging.
   *   <li>ANALYZE {@code stops} so the planner uses the fresh statistics immediately.
   * </ol>
   *
   * <p>The staging table intentionally has no generated columns (no {@code search_text}, uses plain
   * {@code lat}/{@code lng} doubles); PostGIS conversion happens at INSERT time.
   */
  private void replaceCatalog(List<StopDefinition> rows) throws SQLException, IOException {
    try (Connection conn = dataSource.getConnection()) {
      ensureStagingTable(conn);
      truncateStaging(conn);

      log.info("Copying {} NaPTAN rows into stops_staging...", rows.size());
      copyToStaging(conn, rows);

      log.info("Swapping stops_staging -> stops (atomic)...");
      swapStagingToLive(conn);
    }

    analyzeStops();
    log.info("NaPTAN bulk load complete: {} stops loaded.", rows.size());
  }

  private static void ensureStagingTable(Connection conn) throws SQLException {
    String ddl =
        """
        CREATE TABLE IF NOT EXISTS stops_staging (
            atco_code            TEXT NOT NULL,
            common_name          TEXT NOT NULL,
            indicator            TEXT NOT NULL DEFAULT '',
            locality_name        TEXT NOT NULL DEFAULT '',
            parent_locality_name TEXT NOT NULL DEFAULT '',
            lat                  DOUBLE PRECISION NOT NULL,
            lng                  DOUBLE PRECISION NOT NULL,
            stop_type            TEXT NOT NULL,
            crs                  TEXT NOT NULL DEFAULT ''
        )
        """;
    try (Statement st = conn.createStatement()) {
      st.execute(ddl);
    }
  }

  private static void truncateStaging(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("TRUNCATE stops_staging");
    }
  }

  /**
   * Streams rows to Postgres via COPY CSV. Uses the PostgreSQL CopyManager which avoids round-trips
   * for individual INSERTs and is dramatically faster for bulk loads.
   *
   * <p>CSV escaping: fields are double-quoted; embedded double-quotes are doubled ({@code ""}),
   * newlines and NULs are not expected in NaPTAN data but the quoting covers them anyway.
   */
  private static void copyToStaging(Connection conn, List<StopDefinition> rows)
      throws SQLException, IOException {
    String copySql =
        "COPY stops_staging "
            + "(atco_code, common_name, indicator, locality_name, parent_locality_name, lat, lng, stop_type, crs) "
            + "FROM STDIN WITH (FORMAT CSV)";

    CopyManager cm = conn.unwrap(PGConnection.class).getCopyAPI();

    StringBuilder sb = new StringBuilder(rows.size() * 80);
    for (StopDefinition row : rows) {
      appendCsvField(sb, row.atcoCode());
      sb.append(',');
      appendCsvField(sb, row.commonName());
      sb.append(',');
      appendCsvField(sb, row.indicator());
      sb.append(',');
      appendCsvField(sb, row.localityName());
      sb.append(',');
      appendCsvField(sb, row.parentLocalityName());
      sb.append(',');
      sb.append(row.lat());
      sb.append(',');
      sb.append(row.lng());
      sb.append(',');
      appendCsvField(sb, row.stopType());
      sb.append(',');
      appendCsvField(sb, row.crs());
      sb.append('\n');
    }

    Reader reader = new StringReader(sb.toString());
    cm.copyIn(copySql, reader);
  }

  /** Appends a double-quoted CSV field, doubling any embedded double-quotes. */
  private static void appendCsvField(StringBuilder sb, String value) {
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"') sb.append('"'); // double the quote
      sb.append(c);
    }
    sb.append('"');
  }

  private static void swapStagingToLive(Connection conn) throws SQLException {
    conn.setAutoCommit(false);
    try {
      try (Statement st = conn.createStatement()) {
        st.execute("TRUNCATE stops");
        st.execute(
            """
            INSERT INTO stops
                (atco_code, common_name, indicator, locality_name, parent_locality_name,
                 location, stop_type, crs, status, stop_rank)
            SELECT
                atco_code, common_name, indicator, locality_name, parent_locality_name,
                ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography,
                stop_type,
                crs,
                'active',
                CASE WHEN stop_type IN ('RSE', 'RLY', 'MET') THEN 10 ELSE 5 END
            FROM stops_staging
            """);
        st.execute("TRUNCATE stops_staging");
      }
      conn.commit();
    } catch (SQLException e) {
      conn.rollback();
      throw e;
    } finally {
      conn.setAutoCommit(true);
    }
  }

  private void analyzeStops() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {
      st.execute("ANALYZE stops");
    }
  }

  // ---- Row mapping ----

  // ---- DataSourceRegistry: count query ----

  /**
   * Returns the total number of rows in the {@code stops} table. Used by the data-source inventory;
   * the caller is responsible for TTL-caching this to avoid per-request COUNT(*) scans.
   */
  private long queryCount() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM stops");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static Stop mapRow(ResultSet rs) throws SQLException {
    return new Stop(
        rs.getString("atco_code"),
        rs.getString("common_name"),
        rs.getString("indicator"),
        rs.getString("locality_name"),
        rs.getString("stop_type"),
        rs.getDouble("lat"),
        rs.getDouble("lng"),
        rs.getString("crs"));
  }

  @Override
  public long activeStopCount() throws StorageException {
    try {
      return queryCount();
    } catch (SQLException e) {
      throw new StorageException("Catalog count failed", e);
    }
  }

  @Override
  public List<RankedStop> search(String query, int limit) throws StorageException {
    try {
      return querySearch(query, limit);
    } catch (SQLException e) {
      throw new StorageException("Catalog search failed", e);
    }
  }

  @Override
  public void bulkLoad(List<StopDefinition> rows) throws StorageException {
    try {
      replaceCatalog(rows);
    } catch (SQLException | IOException e) {
      throw new StorageException("Catalog import failed", e);
    }
  }

  @Override
  public StopPage inViewport(double minLat, double minLng, double maxLat, double maxLng)
      throws StorageException {
    try {
      return queryViewport(minLat, minLng, maxLat, maxLng);
    } catch (SQLException e) {
      throw new StorageException("Stop viewport query failed", e);
    }
  }
}
