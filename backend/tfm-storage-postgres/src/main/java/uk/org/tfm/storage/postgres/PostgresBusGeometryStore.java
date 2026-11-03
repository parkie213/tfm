package uk.org.tfm.storage.postgres;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.storage.BusGeometryStore;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.timetable.BusRouteLabel;
import uk.org.tfm.model.timetable.BusRoutePattern;
import uk.org.tfm.model.timetable.BusRouteShape;
import uk.org.tfm.model.timetable.RoutePoint;
import uk.org.tfm.model.timetable.SharedRoadSegment;

/**
 * PostGIS geometry queries and writes; routing and shared-edge calculations belong to the
 * application.
 */
public final class PostgresBusGeometryStore implements BusGeometryStore {
  private static final Logger log = LoggerFactory.getLogger(PostgresBusGeometryStore.class);
  private static final double ROUTE_SHAPE_SIMPLIFY_DEG = 0.00002;
  private static final double SHAPE_MATCH_SIMPLIFY_DEG = 0.00012;
  private final DataSource dataSource;

  public PostgresBusGeometryStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  private static final String BUILD_ROUTE_SHAPES_SQL =
      """
      INSERT INTO gtfs_route_shapes (shape_id, route_short_name, route_long_name, geom, source)
      SELECT g.shape_id, r.route_short_name, r.route_long_name, simp.geom, 'gtfs'
      FROM (
        SELECT shape_id,
               ST_MakeLine(ST_SetSRID(ST_MakePoint(lng, lat), 4326) ORDER BY seq) AS geom
        FROM gtfs_shapes
        GROUP BY shape_id
        HAVING count(*) >= 2
      ) g
      JOIN LATERAL (
        SELECT rt.route_short_name, rt.route_long_name
        FROM gtfs_trips t
        JOIN gtfs_routes rt ON rt.route_id = t.route_id
        WHERE t.shape_id = g.shape_id
        LIMIT 1
      ) r ON true
      CROSS JOIN LATERAL (SELECT ST_Simplify(g.geom, %s) AS geom) simp
      WHERE ST_GeometryType(simp.geom) = 'ST_LineString'
        AND ST_NPoints(simp.geom) >= 2
      """
          .formatted(ROUTE_SHAPE_SIMPLIFY_DEG);

  private static final String SHAPELESS_ROUTES_SQL =
      """
      WITH shapeless AS (
        SELECT r.route_id, r.route_short_name, r.route_long_name
        FROM gtfs_routes r
        WHERE NOT EXISTS (SELECT 1 FROM gtfs_trips t WHERE t.route_id = r.route_id AND t.shape_id <> '')
      ),
      rep AS (
        SELECT s.route_short_name, s.route_long_name, rt.trip_id
        FROM shapeless s
        CROSS JOIN LATERAL (
          -- One representative trip per MAJOR pattern (>= 2 trips and >= half the modal pattern's
          -- frequency; the modal always qualifies), mirroring BUNDLE_ROUTES_SQL: a single pick hides
          -- streets other frequent variants serve, while every variant resurrects one-off oddities.
          WITH tp AS (
            SELECT t.trip_id,
                   md5(string_agg(st.stop_id, ',' ORDER BY st.stop_sequence, st.stop_id)) AS pattern,
                   count(*) AS nstops
            FROM gtfs_trips t JOIN gtfs_stop_times st ON st.trip_id = t.trip_id
            WHERE t.route_id = s.route_id
            GROUP BY t.trip_id
          ),
          pf AS (SELECT pattern, count(*) AS freq FROM tp GROUP BY pattern),
          reps AS (
            SELECT DISTINCT ON (tp.pattern) tp.trip_id, tp.nstops, pf.freq
            FROM tp JOIN pf USING (pattern)
            ORDER BY tp.pattern, tp.nstops DESC, tp.trip_id
          ),
          final AS (
            SELECT *, row_number() OVER (ORDER BY freq DESC, nstops DESC, trip_id) AS modal_rank,
                   max(freq) OVER () AS modal_freq
            FROM reps
          )
          SELECT trip_id FROM final
          WHERE modal_rank = 1 OR (freq >= 2 AND freq * 2 >= modal_freq)
        ) rt
      )
      SELECT rep.route_short_name, rep.route_long_name,
             md5(string_agg(st.stop_id, ',' ORDER BY st.stop_sequence, st.stop_id)) AS pattern,
             string_agg(ST_X(sp.location::geometry) || ',' || ST_Y(sp.location::geometry),
                        ';' ORDER BY st.stop_sequence, st.stop_id) AS coords
      FROM rep
      JOIN gtfs_stop_times st ON st.trip_id = rep.trip_id
      JOIN stops sp ON sp.atco_code = st.stop_id
      GROUP BY rep.route_short_name, rep.route_long_name, rep.trip_id
      HAVING count(*) >= 2
      """;

  private static final String BUNDLE_ROUTES_SQL =
      """
      WITH cand AS (
        SELECT DISTINCT r.route_id, r.route_short_name, r.route_long_name
        FROM gtfs_routes r
        JOIN gtfs_trips t ON t.route_id = r.route_id
        JOIN gtfs_stop_times st ON st.trip_id = t.trip_id
        JOIN stops sp ON sp.atco_code = st.stop_id
        WHERE sp.location && ST_MakeEnvelope(?, ?, ?, ?, 4326)
      ),
      rep AS (
        SELECT c.route_short_name, c.route_long_name, rt.trip_id, rt.shape_id
        FROM cand c
        CROSS JOIN LATERAL (
          WITH tp AS (
            SELECT t.trip_id, t.shape_id,
                   md5(string_agg(st.stop_id, ',' ORDER BY st.stop_sequence, st.stop_id)) AS pattern,
                   count(*) AS nstops
            FROM gtfs_trips t JOIN gtfs_stop_times st ON st.trip_id = t.trip_id
            WHERE t.route_id = c.route_id
            GROUP BY t.trip_id, t.shape_id
          ),
          pf AS (SELECT pattern, count(*) AS freq FROM tp GROUP BY pattern),
          reps AS (
            SELECT DISTINCT ON (tp.pattern) tp.trip_id, tp.shape_id, tp.nstops, pf.freq
            FROM tp JOIN pf USING (pattern)
            ORDER BY tp.pattern, (tp.shape_id <> '') DESC, tp.nstops DESC, tp.trip_id
          ),
          final AS (
            SELECT *, row_number() OVER (ORDER BY freq DESC, nstops DESC, trip_id) AS modal_rank,
                   max(freq) OVER () AS modal_freq
            FROM reps
          )
          SELECT trip_id, shape_id FROM final
          WHERE modal_rank = 1 OR (freq >= 2 AND freq * 2 >= modal_freq)
        ) rt
      ),
      shp AS (
        SELECT rep.trip_id,
               ST_AsText(ST_Simplify(
                 ST_MakeLine(ST_SetSRID(ST_MakePoint(sh.lng, sh.lat), 4326) ORDER BY sh.seq),
                 %s)) AS shape_wkt
        FROM rep JOIN gtfs_shapes sh ON sh.shape_id = rep.shape_id
        WHERE rep.shape_id <> ''
        GROUP BY rep.trip_id
      )
      SELECT rep.route_short_name, rep.route_long_name,
             md5(string_agg(st.stop_id, ',' ORDER BY st.stop_sequence, st.stop_id)) AS pattern,
             string_agg(ST_X(sp.location::geometry) || ',' || ST_Y(sp.location::geometry),
                        ';' ORDER BY st.stop_sequence, st.stop_id) AS coords,
             max(shp.shape_wkt) AS shape_wkt
      FROM rep
      JOIN gtfs_stop_times st ON st.trip_id = rep.trip_id
      JOIN stops sp ON sp.atco_code = st.stop_id
      LEFT JOIN shp ON shp.trip_id = rep.trip_id
      GROUP BY rep.route_short_name, rep.route_long_name, rep.trip_id
      HAVING count(*) >= 2
      """
          .formatted(SHAPE_MATCH_SIMPLIFY_DEG);

  private void rebuildPublished() throws SQLException {
    long startMs = System.currentTimeMillis();
    int inserted;
    // Atomic swap of the GTFS-derived rows in ONE transaction: readers see the old 'gtfs' rows
    // until
    // COMMIT, then the new ones — never an empty window. The persisted, expensively road-snapped
    // 'osrm' rows are left untouched (see buildSnappedRoutes()). DELETE+INSERT is fast (no OSRM),
    // so
    // holding the transaction is cheap, unlike the shared-road build (which stages + swaps
    // instead).
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try (Statement st = conn.createStatement()) {
        st.execute("DELETE FROM gtfs_route_shapes WHERE source = 'gtfs'");
        inserted = st.executeUpdate(BUILD_ROUTE_SHAPES_SQL);
        conn.commit();
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(true);
      }
    }
    // ANALYZE after commit, on its own connection (must be outside the load transaction).
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {
      st.execute("ANALYZE gtfs_route_shapes");
    }
    log.info(
        "Built gtfs_route_shapes (gtfs source): {} routes in {}ms.",
        inserted,
        System.currentTimeMillis() - startMs);
  }

  private Map<String, String> loadOperators() throws SQLException {
    Map<String, String> out = new HashMap<>();
    String sql =
        "SELECT r.route_short_name, r.route_long_name, a.agency_name "
            + "FROM gtfs_routes r JOIN gtfs_agency a ON a.agency_id = r.agency_id";
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.putIfAbsent(
            rs.getString(1) + "|" + rs.getString(2),
            rs.getString(3) == null ? "" : rs.getString(3));
      }
    }
    return out;
  }

  /**
   * route "short|long" -> raw GTFS route_color hex (no '#', '' when absent), for trunk building.
   */
  private Map<String, String> loadColors() throws SQLException {
    Map<String, String> out = new HashMap<>();
    String sql = "SELECT route_short_name, route_long_name, route_color FROM gtfs_routes";
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.putIfAbsent(
            rs.getString(1) + "|" + rs.getString(2),
            rs.getString(3) == null ? "" : rs.getString(3));
      }
    }
    return out;
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }

  private <T> T query(SqlWork<T> work) throws StorageException {
    try {
      return work.run();
    } catch (SQLException e) {
      throw new StorageException("Bus geometry storage failed", e);
    }
  }

  @Override
  public void rebuildPublishedShapes() throws StorageException {
    query(
        () -> {
          rebuildPublished();
          return null;
        });
  }

  @Override
  public List<BusRoutePattern> shapelessRoutes() throws StorageException {
    return query(
        () -> {
          var out = new ArrayList<BusRoutePattern>();
          try (var conn = dataSource.getConnection();
              var st = conn.createStatement();
              var rs = st.executeQuery(SHAPELESS_ROUTES_SQL)) {
            while (rs.next()) out.add(pattern(rs, false));
          }
          return out;
        });
  }

  @Override
  public List<BusRoutePattern> routesForBundling(
      double minLat, double minLng, double maxLat, double maxLng) throws StorageException {
    return query(
        () -> {
          var out = new ArrayList<BusRoutePattern>();
          try (var conn = dataSource.getConnection();
              var ps = conn.prepareStatement(BUNDLE_ROUTES_SQL)) {
            ps.setDouble(1, minLng);
            ps.setDouble(2, minLat);
            ps.setDouble(3, maxLng);
            ps.setDouble(4, maxLat);
            try (var rs = ps.executeQuery()) {
              while (rs.next()) out.add(pattern(rs, true));
            }
          }
          return out;
        });
  }

  private static BusRoutePattern pattern(ResultSet rs, boolean withShape) throws SQLException {
    return new BusRoutePattern(
        rs.getString("route_short_name"),
        rs.getString("route_long_name"),
        rs.getString("pattern"),
        parseStops(rs.getString("coords")),
        withShape ? parseShape(rs.getString("shape_wkt")) : List.of());
  }

  @Override
  public Set<String> retainSnappedRoutes(Set<String> currentIds) throws StorageException {
    return query(
        () -> {
          var existing = new HashSet<String>();
          try (var conn = dataSource.getConnection()) {
            try (var ps =
                conn.prepareStatement(
                    "DELETE FROM gtfs_route_shapes WHERE source = 'osrm' AND shape_id <> ALL (?)")) {
              ps.setArray(1, conn.createArrayOf("text", currentIds.toArray(String[]::new)));
              ps.executeUpdate();
            }
            try (var ps =
                    conn.prepareStatement(
                        "SELECT shape_id FROM gtfs_route_shapes WHERE source = 'osrm'");
                var rs = ps.executeQuery()) {
              while (rs.next()) existing.add(rs.getString(1));
            }
          }
          return existing;
        });
  }

  @Override
  public void updateSnappedRouteNames(String id, String shortName, String longName)
      throws StorageException {
    query(
        () -> {
          try (var conn = dataSource.getConnection();
              var ps =
                  conn.prepareStatement(
                      "UPDATE gtfs_route_shapes SET route_short_name = ?, route_long_name = ? WHERE shape_id = ?")) {
            ps.setString(1, shortName);
            ps.setString(2, longName);
            ps.setString(3, id);
            ps.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public boolean saveSnappedRoute(BusRouteShape shape) throws StorageException {
    return query(
        () -> {
          try (var conn = dataSource.getConnection();
              var ps =
                  conn.prepareStatement(
                      "INSERT INTO gtfs_route_shapes (shape_id, route_short_name, route_long_name, geom, source) "
                          + "SELECT ?, ?, ?, g, 'osrm' FROM (SELECT ST_Simplify(ST_GeomFromText(?, 4326), "
                          + ROUTE_SHAPE_SIMPLIFY_DEG
                          + ") AS g) s "
                          + "WHERE g IS NOT NULL AND ST_GeometryType(g) = 'ST_LineString' AND ST_NPoints(g) >= 2 "
                          + "ON CONFLICT (shape_id) DO UPDATE SET route_short_name = EXCLUDED.route_short_name, route_long_name = EXCLUDED.route_long_name")) {
            ps.setString(1, shape.shapeId());
            ps.setString(2, shape.shortName());
            ps.setString(3, shape.longName());
            ps.setString(4, toWkt(shape.points()));
            return ps.executeUpdate() > 0;
          }
        });
  }

  @Override
  public int finishSnappedRoutes(long maxLengthMetres) throws StorageException {
    return query(
        () -> {
          try (var conn = dataSource.getConnection();
              var ps =
                  conn.prepareStatement(
                      "DELETE FROM gtfs_route_shapes WHERE source = 'osrm' AND ST_Length(geom::geography) > ?")) {
            ps.setLong(1, maxLengthMetres);
            int dropped = ps.executeUpdate();
            try (var st = conn.createStatement()) {
              st.execute("ANALYZE gtfs_route_shapes");
            }
            return dropped;
          }
        });
  }

  @Override
  public Map<String, String> routeOperators() throws StorageException {
    return query(this::loadOperators);
  }

  @Override
  public Map<String, String> routeColors() throws StorageException {
    return query(this::loadColors);
  }

  @Override
  public void stageTrunks(List<SharedRoadSegment> segments, long maxLengthMetres)
      throws StorageException {
    query(
        () -> {
          try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
              try (var st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS gtfs_route_trunks_staging");
                st.execute(
                    "CREATE TABLE gtfs_route_trunks_staging (LIKE gtfs_route_trunks INCLUDING DEFAULTS)");
                st.execute(
                    "CREATE INDEX gtfs_route_trunks_staging_geom ON gtfs_route_trunks_staging USING GIST (geom)");
              }
              try (var ps =
                  conn.prepareStatement(
                      "INSERT INTO gtfs_route_trunks_staging (geom, route_short_names, route_long_names, operators, route_colors) VALUES (ST_GeomFromText(?, 4326), ?, ?, ?, ?)")) {
                for (var segment : segments) {
                  ps.setString(1, toWkt(segment.points()));
                  ps.setArray(
                      2,
                      conn.createArrayOf(
                          "text",
                          segment.routes().stream()
                              .map(BusRouteLabel::shortName)
                              .toArray(String[]::new)));
                  ps.setArray(
                      3,
                      conn.createArrayOf(
                          "text",
                          segment.routes().stream()
                              .map(BusRouteLabel::longName)
                              .toArray(String[]::new)));
                  ps.setArray(
                      4,
                      conn.createArrayOf(
                          "text",
                          segment.routes().stream()
                              .map(BusRouteLabel::operator)
                              .toArray(String[]::new)));
                  ps.setArray(
                      5,
                      conn.createArrayOf(
                          "text",
                          segment.routes().stream()
                              .map(BusRouteLabel::color)
                              .toArray(String[]::new)));
                  ps.executeUpdate();
                }
              }
              try (var ps =
                  conn.prepareStatement(
                      "DELETE FROM gtfs_route_trunks_staging WHERE ST_Length(geom::geography) > ?")) {
                ps.setLong(1, maxLengthMetres);
                ps.executeUpdate();
              }
              try (var st = conn.createStatement()) {
                st.execute("ANALYZE gtfs_route_trunks_staging");
              }
              conn.commit();
            } catch (SQLException | RuntimeException e) {
              conn.rollback();
              throw e;
            } finally {
              conn.setAutoCommit(true);
            }
          }
          return null;
        });
  }

  @Override
  public void discardStagedTrunks() throws StorageException {
    query(
        () -> {
          try (var conn = dataSource.getConnection();
              var st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS gtfs_route_trunks_staging");
          }
          return null;
        });
  }

  private static List<RoutePoint> parseStops(String coords) {
    var out = new ArrayList<RoutePoint>();
    for (String pair : coords.split(";")) {
      int comma = pair.indexOf(',');
      if (comma <= 0) continue;
      out.add(
          new RoutePoint(
              Double.parseDouble(pair.substring(comma + 1)),
              Double.parseDouble(pair.substring(0, comma))));
    }
    return out;
  }

  private static List<RoutePoint> parseShape(String wkt) {
    var out = new ArrayList<RoutePoint>();
    if (wkt == null || !wkt.startsWith("LINESTRING")) return out;
    int open = wkt.indexOf('('), close = wkt.lastIndexOf(')');
    if (open < 0 || close <= open) return out;
    for (String pair : wkt.substring(open + 1, close).split(",")) {
      String[] xy = pair.trim().split("\\s+");
      if (xy.length < 2) continue;
      try {
        out.add(new RoutePoint(Double.parseDouble(xy[1]), Double.parseDouble(xy[0])));
      } catch (NumberFormatException ignored) {
      }
    }
    return out;
  }

  private static String toWkt(List<RoutePoint> points) {
    var out = new StringJoiner(",", "LINESTRING(", ")");
    for (var point : points) out.add(point.lng() + " " + point.lat());
    return out.toString();
  }
}
