package uk.org.tfm.server.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sql.DataSource;
import uk.org.tfm.proto.v1.LatLng;
import uk.org.tfm.proto.v1.RouteTrunk;
import uk.org.tfm.server.gtfs.RouteColor;

/**
 * Direct-JDBC DAO for {@code gtfs_route_shapes} — the derived, simplified, GiST-indexed route
 * geometry that powers the togglable "all routes" map overlay.
 *
 * <p>The table is rebuilt once per GTFS load (see {@code
 * PostgresBusGeometryStore.rebuildPublishedShapes}); reads here are a single spatial bounding-box
 * query against the GiST index, mirroring the stop viewport query. Geometry is pulled as GeoJSON
 * and decoded into proto {@link LatLng}s.
 */
@Singleton
public final class RouteShapeDao {

  /** Trunks are more numerous than routes (a route splits into many) but each is small. */
  static final int TRUNK_VIEWPORT_CAP = 1500;

  private static final int TRUNK_VIEWPORT_FETCH = TRUNK_VIEWPORT_CAP + 1;

  private final DataSource dataSource;
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public RouteShapeDao(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Returns up to {@value #TRUNK_VIEWPORT_CAP} shared route trunks whose geometry intersects the
   * bounding box — the multi-route "single line" overlay. The {@code &&} GiST predicate is the
   * access path.
   */
  public TrunkViewportResult trunksInViewport(
      double minLat, double minLng, double maxLat, double maxLng) throws SQLException {
    String sql =
        """
        SELECT ST_AsGeoJSON(geom) AS geojson, route_short_names, route_long_names, operators,
               route_colors
        FROM gtfs_route_trunks
        WHERE geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
        LIMIT ?
        """;
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setDouble(1, minLng);
      ps.setDouble(2, minLat);
      ps.setDouble(3, maxLng);
      ps.setDouble(4, maxLat);
      ps.setInt(5, TRUNK_VIEWPORT_FETCH);

      List<RouteTrunk> trunks = new ArrayList<>(TRUNK_VIEWPORT_CAP);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          RouteTrunk t = mapTrunkRow(rs);
          if (t != null) {
            trunks.add(t);
          }
        }
      }
      boolean truncated = trunks.size() > TRUNK_VIEWPORT_CAP;
      if (truncated) {
        trunks = trunks.subList(0, TRUNK_VIEWPORT_CAP);
      }
      if (trunks.isEmpty()) {
        return publishedShapesInViewport(minLat, minLng, maxLat, maxLng);
      }
      return new TrunkViewportResult(trunks, truncated);
    }
  }

  /** Published GTFS shapes remain visible when optional OSRM bundling is unavailable. */
  private TrunkViewportResult publishedShapesInViewport(
      double minLat, double minLng, double maxLat, double maxLng) throws SQLException {
    String sql =
        """
        SELECT ST_AsGeoJSON(s.geom) AS geojson,
               ARRAY[s.route_short_name] AS route_short_names,
               ARRAY[s.route_long_name] AS route_long_names,
               ARRAY[COALESCE(op.agency_name, '')] AS operators,
               ARRAY[CASE WHEN op.route_color ~ '^[0-9A-Fa-f]{6}$'
                          THEN '#' || lower(op.route_color) ELSE '' END] AS route_colors
        FROM gtfs_route_shapes s
        LEFT JOIN LATERAL (
          SELECT a.agency_name, r.route_color
          FROM gtfs_routes r
          LEFT JOIN gtfs_agency a ON a.agency_id = r.agency_id
          WHERE r.route_short_name = s.route_short_name
            AND r.route_long_name = s.route_long_name
          ORDER BY r.route_id
          LIMIT 1
        ) op ON true
        WHERE s.geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
        ORDER BY s.shape_id
        LIMIT ?
        """;
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setDouble(1, minLng);
      ps.setDouble(2, minLat);
      ps.setDouble(3, maxLng);
      ps.setDouble(4, maxLat);
      ps.setInt(5, TRUNK_VIEWPORT_FETCH);
      List<RouteTrunk> trunks = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          RouteTrunk trunk = mapTrunkRow(rs);
          if (trunk != null) trunks.add(trunk);
        }
      }
      boolean truncated = trunks.size() > TRUNK_VIEWPORT_CAP;
      return new TrunkViewportResult(
          truncated ? trunks.subList(0, TRUNK_VIEWPORT_CAP) : trunks, truncated);
    }
  }

  /** Decodes one trunk row (geometry + four parallel route-aligned TEXT[] arrays); null if bad. */
  private RouteTrunk mapTrunkRow(ResultSet rs) throws SQLException {
    String geojson = rs.getString("geojson");
    if (geojson == null || geojson.isBlank()) {
      return null;
    }
    String[] shortNames = toStringArray(rs.getArray("route_short_names"));
    String[] longNames = toStringArray(rs.getArray("route_long_names"));
    String[] operators = toStringArray(rs.getArray("operators"));
    if (shortNames == null
        || longNames == null
        || operators == null
        || shortNames.length == 0
        || shortNames.length != longNames.length
        || shortNames.length != operators.length) {
      return null; // arrays must be present and equal length (one entry per route)
    }
    // route_colors is populated by the GTFS ingest; until the first post-migration reload it may be
    // empty/short, so a per-route colour missing from storage is derived from the short name (the
    // same
    // hash the builder uses for feeds with no official colour) — the proto colour is never blank.
    String[] colors = toStringArray(rs.getArray("route_colors"));
    RouteTrunk.Builder builder = RouteTrunk.newBuilder();
    try {
      JsonNode coords = mapper.readTree(geojson).get("coordinates");
      if (coords == null || !coords.isArray()) {
        return null;
      }
      // GeoJSON LineString coordinates are ordered [lng, lat] pairs.
      for (JsonNode pt : coords) {
        builder.addShape(
            LatLng.newBuilder().setLng(pt.get(0).asDouble()).setLat(pt.get(1).asDouble()).build());
      }
    } catch (Exception e) {
      return null;
    }
    if (builder.getShapeCount() < 2) {
      return null;
    }
    for (int i = 0; i < shortNames.length; i++) {
      String sn = shortNames[i] == null ? "" : shortNames[i];
      builder.addRouteShortName(sn);
      builder.addRouteLongName(longNames[i] == null ? "" : longNames[i]);
      builder.addOperator(operators[i] == null ? "" : operators[i]);
      String color = colors != null && i < colors.length ? colors[i] : null;
      builder.addRouteColor(color == null || color.isBlank() ? RouteColor.hashColor(sn) : color);
    }
    return builder.build();
  }

  /** Unwraps a SQL text array to {@code String[]}, or null if absent. */
  private static String[] toStringArray(Array arr) throws SQLException {
    if (arr == null) {
      return null;
    }
    Object raw = arr.getArray();
    if (raw instanceof String[] a) {
      return a;
    }
    if (raw instanceof Object[] o) {
      String[] s = new String[o.length];
      for (int i = 0; i < o.length; i++) {
        s[i] = o[i] == null ? null : o[i].toString();
      }
      return s;
    }
    return null;
  }

  /** Result carrier for {@link #trunksInViewport}. */
  public record TrunkViewportResult(List<RouteTrunk> trunks, boolean truncated) {}
}
