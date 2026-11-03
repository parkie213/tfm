package uk.org.tfm.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import uk.org.tfm.model.timetable.*;

/** Journey headers, calling points, supplied shapes, and representative route-trip lookup. */
final class TimetableJourneyQueries {
  private final DataSource dataSource;

  TimetableJourneyQueries(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

  // ---- Service detail (a trip's calling points) ----

  private static final String SERVICE_HEADER_SQL =
      "SELECT r.route_short_name, t.trip_headsign, a.agency_name "
          + "FROM gtfs_trips t "
          + "JOIN gtfs_routes r ON r.route_id = t.route_id "
          + "JOIN gtfs_agency a ON a.agency_id = r.agency_id "
          + "WHERE t.trip_id = ? LIMIT 1";

  private static final String SERVICE_STOPS_SQL =
      "SELECT st.stop_sequence, st.departure_secs, st.stop_id, "
          + "       s.common_name, s.locality_name, "
          + "       ST_Y(s.location::geometry) AS lat, ST_X(s.location::geometry) AS lng "
          + "FROM gtfs_stop_times st "
          + "LEFT JOIN stops s ON s.atco_code = st.stop_id "
          + "WHERE st.trip_id = ? "
          + "ORDER BY st.stop_sequence";

  private static final String SERVICE_SHAPE_SQL =
      "SELECT sh.lat, sh.lng "
          + "FROM gtfs_trips t JOIN gtfs_shapes sh ON sh.shape_id = t.shape_id "
          + "WHERE t.trip_id = ? ORDER BY sh.seq";

  // Pick one representative trip for a named route — the fullest variant (most calling points) so
  // the
  // route panel shows the complete loop rather than a short-working. operator (the agency name
  // carried
  // on the clicked trunk) is the real discriminator for a number reused across operators, since
  // route_long_name is usually empty in GTFS; both are optional ("" matches any). Without the
  // operator
  // filter a number like "8" matches every operator's route 8 and the ORDER BY picks the largest
  // network's, not the one clicked.
  private static final String REPRESENTATIVE_TRIP_SQL =
      "SELECT t.trip_id "
          + "FROM gtfs_routes r "
          + "JOIN gtfs_trips t ON t.route_id = r.route_id "
          + "LEFT JOIN gtfs_agency a ON a.agency_id = r.agency_id "
          + "WHERE r.route_short_name = ? "
          + "  AND (? = '' OR r.route_long_name = ?) "
          + "  AND (? = '' OR a.agency_name = ?) "
          + "ORDER BY (SELECT count(*) FROM gtfs_stop_times st WHERE st.trip_id = t.trip_id) DESC, t.trip_id "
          + "LIMIT 1";

  /** Returns the ordered calling points (with scheduled times) for one trip. Empty if unknown. */
  JourneyDetails queryServiceStops(String tripId) throws SQLException {
    String line = "", destination = "", operator = "";
    List<CallingPoint> stops = new ArrayList<>();
    List<RoutePoint> shape = new ArrayList<>();
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement ps = conn.prepareStatement(SERVICE_HEADER_SQL)) {
        ps.setString(1, tripId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            line = nz(rs.getString(1));
            destination = nz(rs.getString(2));
            operator = nz(rs.getString(3));
          }
        }
      }
      try (PreparedStatement ps = conn.prepareStatement(SERVICE_STOPS_SQL)) {
        ps.setString(1, tripId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String atco = nz(rs.getString(3));
            String name = rs.getString(4);
            double lat = rs.getDouble(6);
            boolean latNull = rs.wasNull();
            double lng = rs.getDouble(7);
            boolean hasCoords = !latNull && !rs.wasNull();
            stops.add(
                new CallingPoint(
                    rs.getInt(1),
                    atco,
                    name != null && !name.isBlank() ? name : atco,
                    nz(rs.getString(5)),
                    LocalTime.ofSecondOfDay(rs.getInt(2) % 86400).format(HH_MM),
                    hasCoords ? lat : 0,
                    hasCoords ? lng : 0));
          }
        }
      }
      try (PreparedStatement ps = conn.prepareStatement(SERVICE_SHAPE_SQL)) {
        ps.setString(1, tripId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) shape.add(new RoutePoint(rs.getDouble(1), rs.getDouble(2)));
        }
      }
    }
    return new JourneyDetails(line, destination, operator, stops, shape);
  }

  /**
   * Calling points for a whole route, picked by clicking its line on the map. Resolves a
   * representative trip (the fullest variant — see {@link #REPRESENTATIVE_TRIP_SQL}) for the named
   * route, then delegates to {@link #queryServiceStops(String)}. Returns an empty response if the
   * route name matches no trip. {@code longName} may be empty to match on the short name alone.
   */
  JourneyDetails queryRouteStops(String shortName, String longName, String operator)
      throws SQLException {
    String tripId = null;
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(REPRESENTATIVE_TRIP_SQL)) {
      ps.setString(1, shortName);
      ps.setString(2, longName);
      ps.setString(3, longName);
      ps.setString(4, operator);
      ps.setString(5, operator);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          tripId = rs.getString(1);
        }
      }
    }
    if (tripId == null) {
      return JourneyDetails.EMPTY;
    }
    return queryServiceStops(tripId);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
