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
import uk.org.tfm.model.catalog.Locality;
import uk.org.tfm.model.catalog.LocalityDefinition;
import uk.org.tfm.model.catalog.RankedPlace;
import uk.org.tfm.model.storage.LocalityStore;
import uk.org.tfm.model.storage.StorageException;

/**
 * Direct-JDBC DAO for the {@code nptg_localities} table (NPTG gazetteer of places, PostGIS-backed).
 * Mirrors {@link PostgresStopStore}: a trigram {@link #search} for autocomplete and an atomic
 * {@link #bulkLoad}. Place results are surfaced above stops in the search dropdown so typing a town
 * name offers the town itself first.
 *
 * <p>Source coordinates are British National Grid (EPSG:27700); they are reprojected to WGS84 at
 * INSERT via {@code ST_Transform}, so the staging table holds raw easting/northing doubles.
 */
public final class PostgresLocalityStore implements LocalityStore {

  private static final Logger log = LoggerFactory.getLogger(PostgresLocalityStore.class);

  static final int SEARCH_MAX_LIMIT = 10;
  static final int SEARCH_DEFAULT_LIMIT = 3;

  // Melton Mowbray home point: equally-good name matches sort nearest-first (Melton Mowbray before
  // Melton in E Yorks). Same anchor and proximity penalty as PostgresStopStore, so place and stop
  // scores are
  // directly comparable when the service interleaves them.
  private static final double MELTON_LNG = -0.88;
  private static final double MELTON_LAT = 52.77;
  private static final double MELTON_BIAS_WEIGHT = 0.2;
  private static final double MELTON_BIAS_SCALE_M = 30_000.0;
  private static final double WORD_SIM_THRESHOLD = 0.5;

  /**
   * Hard catchment radius (metres): only localities within this distance of Melton are returned, so
   * a far-off town doesn't outrank a local one. Matches {@code PostgresStopStore.SEARCH_RADIUS_M} —
   * places and stops are scoped to the same local network.
   */
  private static final double SEARCH_RADIUS_M = 45_000.0;

  /**
   * Name-match floor at/above which a place is a "strong" match — the query really names this town,
   * so it earns the top tier (above its own station; see the service merge). Below it the place
   * still matches (down to {@link #WORD_SIM_THRESHOLD}) but ranks at the bus tier, where a poor
   * place match sinks beneath real stops and is usually dropped by the result cap. This is what
   * stops a weakly name-similar village from jumping above an exact-match stop on an ordinary stop
   * search.
   */
  private static final double STRONG_PLACE_THRESHOLD = 0.8;

  private final DataSource dataSource;

  public PostgresLocalityStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  // ---- Trigram search ----

  /**
   * Name autocomplete: matches the query against the locality NAME (not qualifier/parent — see
   * {@code V10}), ranked by name word-similarity, then nearest to Melton as a tie-break. So typing
   * a town returns that town, not a village whose district happens to share the word. Returns an
   * empty list for queries shorter than 2 characters.
   */
  private List<RankedPlace> querySearch(String query, int limit) throws SQLException {
    String q = query.trim().toLowerCase();
    if (q.length() < 2) {
      return List.of();
    }
    int cap = (limit <= 0) ? SEARCH_DEFAULT_LIMIT : Math.min(limit, SEARCH_MAX_LIMIT);

    // `lower(name) %> ?` uses the GIN trigram index on lower(name). word similarity and distance
    // are
    // also SELECTed (the service turns them into the unified tier/score); the ORDER BY repeats them
    // because an output alias can't be reused inside an expression. Nearest-to-Melton breaks ties
    // between equally-good name matches.
    String meltonPoint =
        "ST_SetSRID(ST_MakePoint(" + MELTON_LNG + ", " + MELTON_LAT + "), 4326)::geography";
    String sql =
        "SELECT code, name, qualifier, "
            + "       ST_Y(location::geometry) AS lat, "
            + "       ST_X(location::geometry) AS lng, "
            + "       word_similarity(?, lower(name)) AS ws, "
            + "       ST_Distance(location, "
            + meltonPoint
            + ") AS dist_m "
            + "FROM nptg_localities "
            + "WHERE lower(name) %> ? "
            // Catchment scope (matches PostgresStopStore): only localities within the local
            // network.
            + "  AND ST_DWithin(location, "
            + meltonPoint
            + ", "
            + SEARCH_RADIUS_M
            + ") "
            + "ORDER BY word_similarity(?, lower(name)) DESC, "
            + "         ST_Distance(location, "
            + meltonPoint
            + ") ASC "
            + "LIMIT ?";

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

          List<RankedPlace> results = new ArrayList<>(cap);
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              Locality place =
                  new Locality(
                      rs.getString("code"),
                      rs.getString("name"),
                      rs.getString("qualifier"),
                      rs.getDouble("lat"),
                      rs.getDouble("lng"));
              double ws = rs.getDouble("ws");
              double score =
                  ws
                      - MELTON_BIAS_WEIGHT
                          * Math.min(rs.getDouble("dist_m") / MELTON_BIAS_SCALE_M, 1.0);
              // Strong name match → top tier (above its station); otherwise the bus tier, where a
              // weak
              // place sinks below real stops. rankBucket is 0 for places (they have no stop_rank).
              int tier = ws >= STRONG_PLACE_THRESHOLD ? 3 : 0;
              results.add(new RankedPlace(place, tier, score));
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

  // ---- Bulk load ----

  /**
   * Atomically replaces the contents of {@code nptg_localities} with {@code rows} via a staging
   * table (TRUNCATE + COPY → txn TRUNCATE live + INSERT with ST_Transform 27700→4326 → ANALYZE),
   * mirroring {@link PostgresStopStore#bulkLoad}.
   */
  private void replaceCatalog(List<LocalityDefinition> rows) throws SQLException, IOException {
    try (Connection conn = dataSource.getConnection()) {
      ensureStagingTable(conn);
      try (Statement st = conn.createStatement()) {
        st.execute("TRUNCATE nptg_localities_staging");
      }
      log.info("Copying {} NPTG localities into nptg_localities_staging...", rows.size());
      copyToStaging(conn, rows);
      log.info("Swapping nptg_localities_staging -> nptg_localities (atomic)...");
      swapStagingToLive(conn);
    }
    analyze();
    log.info("NPTG localities bulk load complete: {} localities loaded.", rows.size());
  }

  private static void ensureStagingTable(Connection conn) throws SQLException {
    String ddl =
        """
        CREATE TABLE IF NOT EXISTS nptg_localities_staging (
            code        TEXT NOT NULL,
            name        TEXT NOT NULL,
            qualifier   TEXT NOT NULL DEFAULT '',
            parent_name TEXT NOT NULL DEFAULT '',
            easting     DOUBLE PRECISION NOT NULL,
            northing    DOUBLE PRECISION NOT NULL
        )
        """;
    try (Statement st = conn.createStatement()) {
      st.execute(ddl);
    }
  }

  private static void copyToStaging(Connection conn, List<LocalityDefinition> rows)
      throws SQLException, IOException {
    String copySql =
        "COPY nptg_localities_staging "
            + "(code, name, qualifier, parent_name, easting, northing) "
            + "FROM STDIN WITH (FORMAT CSV)";
    CopyManager cm = conn.unwrap(PGConnection.class).getCopyAPI();

    StringBuilder sb = new StringBuilder(rows.size() * 64);
    for (LocalityDefinition row : rows) {
      appendCsvField(sb, row.code());
      sb.append(',');
      appendCsvField(sb, row.name());
      sb.append(',');
      appendCsvField(sb, row.qualifier());
      sb.append(',');
      appendCsvField(sb, row.parentName());
      sb.append(',');
      sb.append(row.easting());
      sb.append(',');
      sb.append(row.northing());
      sb.append('\n');
    }
    Reader reader = new StringReader(sb.toString());
    cm.copyIn(copySql, reader);
  }

  private static void appendCsvField(StringBuilder sb, String value) {
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"') sb.append('"');
      sb.append(c);
    }
    sb.append('"');
  }

  private static void swapStagingToLive(Connection conn) throws SQLException {
    conn.setAutoCommit(false);
    try {
      try (Statement st = conn.createStatement()) {
        st.execute("TRUNCATE nptg_localities");
        st.execute(
            """
            INSERT INTO nptg_localities (code, name, qualifier, parent_name, location)
            SELECT code, name, qualifier, parent_name,
                   ST_Transform(ST_SetSRID(ST_MakePoint(easting, northing), 27700), 4326)::geography
            FROM nptg_localities_staging
            """);
        st.execute("TRUNCATE nptg_localities_staging");
      }
      conn.commit();
    } catch (SQLException e) {
      conn.rollback();
      throw e;
    } finally {
      conn.setAutoCommit(true);
    }
  }

  private void analyze() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {
      st.execute("ANALYZE nptg_localities");
    }
  }

  // ---- DataSourceRegistry: count query ----

  /**
   * Returns the total number of rows in the {@code nptg_localities} table. Used by the data-source
   * inventory; the caller is responsible for TTL-caching this.
   */
  private long queryCount() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM nptg_localities");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Override
  public long localityCount() throws StorageException {
    try {
      return queryCount();
    } catch (SQLException e) {
      throw new StorageException("Catalog count failed", e);
    }
  }

  @Override
  public List<RankedPlace> search(String query, int limit) throws StorageException {
    try {
      return querySearch(query, limit);
    } catch (SQLException e) {
      throw new StorageException("Catalog search failed", e);
    }
  }

  @Override
  public void bulkLoad(List<LocalityDefinition> rows) throws StorageException {
    try {
      replaceCatalog(rows);
    } catch (SQLException | IOException e) {
      throw new StorageException("Catalog import failed", e);
    }
  }
}
