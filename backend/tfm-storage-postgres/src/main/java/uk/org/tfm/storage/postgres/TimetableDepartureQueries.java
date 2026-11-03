package uk.org.tfm.storage.postgres;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import uk.org.tfm.model.timetable.*;

/**
 * Direct-JDBC DAO for scheduled bus departures, backed by the GTFS tables loaded by the timetable
 * import capability.
 *
 * <p>Two query modes, both driven by the composite index {@code gtfs_stop_times(stop_id,
 * departure_secs)}:
 *
 * <ul>
 *   <li><b>Upcoming</b> ({@code date} blank): next departures from now. Before 03:00 local time, a
 *       UNION ALL branch also picks up after-midnight services from the previous service day
 *       ({@code departure_secs >= 86400}).
 *   <li><b>Whole-day</b> ({@code date} = "yyyy-MM-dd"): all services running on that date,
 *       including after-midnight continuations from the previous service day.
 * </ul>
 *
 * <p>Calendar logic lives entirely in SQL via LEFT JOINs on {@code gtfs_calendar} and {@code
 * gtfs_calendar_dates}, mirroring the GTFS spec priority: exception_type=2 (removed) beats
 * exception_type=1 (added) beats base calendar.
 */
final class TimetableDepartureQueries {
  private final DataSource dataSource;

  TimetableDepartureQueries(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

  /** Before 03:00 we also include after-midnight continuations from yesterday's service day. */
  private static final int AFTER_MIDNIGHT_THRESHOLD_SECS = 3 * 3600;

  // ---- SQL constants ----
  // Each constant represents one branch of a UNION. Parameters are commented inline.
  // IMPORTANT: all branches must be kept structurally identical so the UNION works.

  /**
   * Today's services (departure_secs < 86400) from a lower bound. Params: (1) service_date, (2)
   * service_date, (3) stop_id, (4) now_secs lower bound, (5) service_date, (6) service_date
   */
  private static final String TODAY_FROM_NOW_SQL =
      """
      SELECT st.departure_secs,
             r.route_short_name AS line,
             t.trip_headsign    AS destination,
             a.agency_name      AS operator,
             t.trip_id
      FROM   gtfs_stop_times st
      JOIN   gtfs_trips  t ON t.trip_id   = st.trip_id
      JOIN   gtfs_routes r ON r.route_id  = t.route_id
      JOIN   gtfs_agency a ON a.agency_id = r.agency_id
      LEFT JOIN gtfs_calendar cal
             ON cal.service_id = t.service_id
      LEFT JOIN gtfs_calendar_dates cd_rem
             ON cd_rem.service_id = t.service_id AND cd_rem.exception_date = ? AND cd_rem.exception_type = 2
      LEFT JOIN gtfs_calendar_dates cd_add
             ON cd_add.service_id = t.service_id AND cd_add.exception_date = ? AND cd_add.exception_type = 1
      WHERE  st.stop_id        = ?
        AND  st.departure_secs >= ?
        AND  st.departure_secs <  86400
        AND  cd_rem.exception_date IS NULL
        AND  (cd_add.exception_date IS NOT NULL
              OR (cal.service_id IS NOT NULL
                  AND ? BETWEEN cal.start_date AND cal.end_date
                  AND CASE EXTRACT(ISODOW FROM ?::date)
                        WHEN 1 THEN cal.monday WHEN 2 THEN cal.tuesday WHEN 3 THEN cal.wednesday
                        WHEN 4 THEN cal.thursday WHEN 5 THEN cal.friday
                        WHEN 6 THEN cal.saturday WHEN 7 THEN cal.sunday END))""";

  /**
   * Today's services with a lookahead upper bound added. Params: (1) service_date, (2)
   * service_date, (3) stop_id, (4) now_secs lower bound, (5) now_secs upper bound, (6)
   * service_date, (7) service_date
   */
  private static final String TODAY_FROM_NOW_WITH_CAP_SQL =
      """
      SELECT st.departure_secs,
             r.route_short_name AS line,
             t.trip_headsign    AS destination,
             a.agency_name      AS operator,
             t.trip_id
      FROM   gtfs_stop_times st
      JOIN   gtfs_trips  t ON t.trip_id   = st.trip_id
      JOIN   gtfs_routes r ON r.route_id  = t.route_id
      JOIN   gtfs_agency a ON a.agency_id = r.agency_id
      LEFT JOIN gtfs_calendar cal
             ON cal.service_id = t.service_id
      LEFT JOIN gtfs_calendar_dates cd_rem
             ON cd_rem.service_id = t.service_id AND cd_rem.exception_date = ? AND cd_rem.exception_type = 2
      LEFT JOIN gtfs_calendar_dates cd_add
             ON cd_add.service_id = t.service_id AND cd_add.exception_date = ? AND cd_add.exception_type = 1
      WHERE  st.stop_id        = ?
        AND  st.departure_secs >= ?
        AND  st.departure_secs <= ?
        AND  st.departure_secs <  86400
        AND  cd_rem.exception_date IS NULL
        AND  (cd_add.exception_date IS NOT NULL
              OR (cal.service_id IS NOT NULL
                  AND ? BETWEEN cal.start_date AND cal.end_date
                  AND CASE EXTRACT(ISODOW FROM ?::date)
                        WHEN 1 THEN cal.monday WHEN 2 THEN cal.tuesday WHEN 3 THEN cal.wednesday
                        WHEN 4 THEN cal.thursday WHEN 5 THEN cal.friday
                        WHEN 6 THEN cal.saturday WHEN 7 THEN cal.sunday END))""";

  /**
   * After-midnight branch (upcoming mode): yesterday's service day, departure_secs >= 86400. The
   * lower bound (86400 + nowSecs) ensures we only show services not yet passed. Params: (1)
   * yesterday, (2) yesterday, (3) stop_id, (4) lower bound (86400 + nowSecs), (5) yesterday, (6)
   * yesterday
   */
  private static final String AFTER_MIDNIGHT_FROM_NOW_SQL =
      """
      SELECT st.departure_secs,
             r.route_short_name AS line,
             t.trip_headsign    AS destination,
             a.agency_name      AS operator,
             t.trip_id
      FROM   gtfs_stop_times st
      JOIN   gtfs_trips  t ON t.trip_id   = st.trip_id
      JOIN   gtfs_routes r ON r.route_id  = t.route_id
      JOIN   gtfs_agency a ON a.agency_id = r.agency_id
      LEFT JOIN gtfs_calendar cal
             ON cal.service_id = t.service_id
      LEFT JOIN gtfs_calendar_dates cd_rem
             ON cd_rem.service_id = t.service_id AND cd_rem.exception_date = ? AND cd_rem.exception_type = 2
      LEFT JOIN gtfs_calendar_dates cd_add
             ON cd_add.service_id = t.service_id AND cd_add.exception_date = ? AND cd_add.exception_type = 1
      WHERE  st.stop_id        = ?
        AND  st.departure_secs >= ?
        AND  cd_rem.exception_date IS NULL
        AND  (cd_add.exception_date IS NOT NULL
              OR (cal.service_id IS NOT NULL
                  AND ? BETWEEN cal.start_date AND cal.end_date
                  AND CASE EXTRACT(ISODOW FROM ?::date)
                        WHEN 1 THEN cal.monday WHEN 2 THEN cal.tuesday WHEN 3 THEN cal.wednesday
                        WHEN 4 THEN cal.thursday WHEN 5 THEN cal.friday
                        WHEN 6 THEN cal.saturday WHEN 7 THEN cal.sunday END))""";

  /**
   * After-midnight with lookahead cap. Params: (1) yesterday, (2) yesterday, (3) stop_id, (4) lower
   * bound (86400 + nowSecs), (5) upper bound (86400 + nowSecs + cap), (6) yesterday, (7) yesterday
   */
  private static final String AFTER_MIDNIGHT_FROM_NOW_WITH_CAP_SQL =
      """
      SELECT st.departure_secs,
             r.route_short_name AS line,
             t.trip_headsign    AS destination,
             a.agency_name      AS operator,
             t.trip_id
      FROM   gtfs_stop_times st
      JOIN   gtfs_trips  t ON t.trip_id   = st.trip_id
      JOIN   gtfs_routes r ON r.route_id  = t.route_id
      JOIN   gtfs_agency a ON a.agency_id = r.agency_id
      LEFT JOIN gtfs_calendar cal
             ON cal.service_id = t.service_id
      LEFT JOIN gtfs_calendar_dates cd_rem
             ON cd_rem.service_id = t.service_id AND cd_rem.exception_date = ? AND cd_rem.exception_type = 2
      LEFT JOIN gtfs_calendar_dates cd_add
             ON cd_add.service_id = t.service_id AND cd_add.exception_date = ? AND cd_add.exception_type = 1
      WHERE  st.stop_id        = ?
        AND  st.departure_secs >= ?
        AND  st.departure_secs <= ?
        AND  cd_rem.exception_date IS NULL
        AND  (cd_add.exception_date IS NOT NULL
              OR (cal.service_id IS NOT NULL
                  AND ? BETWEEN cal.start_date AND cal.end_date
                  AND CASE EXTRACT(ISODOW FROM ?::date)
                        WHEN 1 THEN cal.monday WHEN 2 THEN cal.tuesday WHEN 3 THEN cal.wednesday
                        WHEN 4 THEN cal.thursday WHEN 5 THEN cal.friday
                        WHEN 6 THEN cal.saturday WHEN 7 THEN cal.sunday END))""";

  /**
   * Whole-day: all departures on a given date, departure_secs < 86400. Params: (1) date, (2) date,
   * (3) stop_id, (4) date, (5) date
   */
  private static final String WHOLE_DAY_SQL =
      """
      SELECT st.departure_secs,
             r.route_short_name AS line,
             t.trip_headsign    AS destination,
             a.agency_name      AS operator,
             t.trip_id
      FROM   gtfs_stop_times st
      JOIN   gtfs_trips  t ON t.trip_id   = st.trip_id
      JOIN   gtfs_routes r ON r.route_id  = t.route_id
      JOIN   gtfs_agency a ON a.agency_id = r.agency_id
      LEFT JOIN gtfs_calendar cal
             ON cal.service_id = t.service_id
      LEFT JOIN gtfs_calendar_dates cd_rem
             ON cd_rem.service_id = t.service_id AND cd_rem.exception_date = ? AND cd_rem.exception_type = 2
      LEFT JOIN gtfs_calendar_dates cd_add
             ON cd_add.service_id = t.service_id AND cd_add.exception_date = ? AND cd_add.exception_type = 1
      WHERE  st.stop_id        = ?
        AND  st.departure_secs <  86400
        AND  cd_rem.exception_date IS NULL
        AND  (cd_add.exception_date IS NOT NULL
              OR (cal.service_id IS NOT NULL
                  AND ? BETWEEN cal.start_date AND cal.end_date
                  AND CASE EXTRACT(ISODOW FROM ?::date)
                        WHEN 1 THEN cal.monday WHEN 2 THEN cal.tuesday WHEN 3 THEN cal.wednesday
                        WHEN 4 THEN cal.thursday WHEN 5 THEN cal.friday
                        WHEN 6 THEN cal.saturday WHEN 7 THEN cal.sunday END))""";

  /**
   * Whole-day after-midnight: previous service day, departure_secs >= 86400. Params: (1) prevDay,
   * (2) prevDay, (3) stop_id, (4) prevDay, (5) prevDay
   */
  private static final String WHOLE_DAY_AFTER_MIDNIGHT_SQL =
      """
      SELECT st.departure_secs,
             r.route_short_name AS line,
             t.trip_headsign    AS destination,
             a.agency_name      AS operator,
             t.trip_id
      FROM   gtfs_stop_times st
      JOIN   gtfs_trips  t ON t.trip_id   = st.trip_id
      JOIN   gtfs_routes r ON r.route_id  = t.route_id
      JOIN   gtfs_agency a ON a.agency_id = r.agency_id
      LEFT JOIN gtfs_calendar cal
             ON cal.service_id = t.service_id
      LEFT JOIN gtfs_calendar_dates cd_rem
             ON cd_rem.service_id = t.service_id AND cd_rem.exception_date = ? AND cd_rem.exception_type = 2
      LEFT JOIN gtfs_calendar_dates cd_add
             ON cd_add.service_id = t.service_id AND cd_add.exception_date = ? AND cd_add.exception_type = 1
      WHERE  st.stop_id        = ?
        AND  st.departure_secs >= 86400
        AND  cd_rem.exception_date IS NULL
        AND  (cd_add.exception_date IS NOT NULL
              OR (cal.service_id IS NOT NULL
                  AND ? BETWEEN cal.start_date AND cal.end_date
                  AND CASE EXTRACT(ISODOW FROM ?::date)
                        WHEN 1 THEN cal.monday WHEN 2 THEN cal.tuesday WHEN 3 THEN cal.wednesday
                        WHEN 4 THEN cal.thursday WHEN 5 THEN cal.friday
                        WHEN 6 THEN cal.saturday WHEN 7 THEN cal.sunday END))""";

  /**
   * Returns scheduled bus departures for the given ATCO stop code. Returns an empty board for
   * unknown stops or when the GTFS tables have not yet been loaded — never throws for "no data".
   *
   * @param stopId NaPTAN ATCO code (== GTFS stop_id)
   * @param numRows 0 = default (10) in upcoming mode / no cap in whole-day mode
   * @param lookaheadMins upcoming mode only: 0 = no time cap
   * @param date "yyyy-MM-dd" for whole-day, blank/null = upcoming
   */
  ScheduledBoard queryDepartures(String stopId, int numRows, int lookaheadMins, String date)
      throws SQLException {

    ZonedDateTime now = ZonedDateTime.now(LONDON);
    String generatedAt = now.format(RFC3339);
    String stopName = lookupStopName(stopId);

    LocalDate wholeDay = parseDate(date);
    List<ScheduledDeparture> departures;
    if (wholeDay != null) {
      departures = queryWholeDay(stopId, wholeDay, numRows);
    } else {
      int rows = numRows > 0 ? numRows : 10;
      departures = queryUpcoming(stopId, now, rows, lookaheadMins);
    }

    return new ScheduledBoard(stopName, generatedAt, departures);
  }

  // ---- Stop name ----

  private String lookupStopName(String stopId) throws SQLException {
    String sql = "SELECT common_name FROM stops WHERE atco_code = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, stopId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : "";
      }
    }
  }

  // ---- Upcoming ----

  private List<ScheduledDeparture> queryUpcoming(
      String stopId, ZonedDateTime now, int limit, int lookaheadMins) throws SQLException {

    LocalDate today = now.toLocalDate();
    int nowSecs = now.toLocalTime().toSecondOfDay();
    Date todaySql = Date.valueOf(today);
    boolean hasLookahead = lookaheadMins > 0;
    boolean addAfterMidnight = nowSecs < AFTER_MIDNIGHT_THRESHOLD_SECS;

    // Choose the right SQL variant for the today branch.
    String todayBranch = hasLookahead ? TODAY_FROM_NOW_WITH_CAP_SQL : TODAY_FROM_NOW_SQL;

    String fullSql;
    if (addAfterMidnight) {
      String amBranch =
          hasLookahead ? AFTER_MIDNIGHT_FROM_NOW_WITH_CAP_SQL : AFTER_MIDNIGHT_FROM_NOW_SQL;
      fullSql =
          "(" + todayBranch + ")\nUNION ALL\n(" + amBranch + ")\nORDER BY departure_secs\nLIMIT ?";
    } else {
      fullSql = todayBranch + "\nORDER BY departure_secs\nLIMIT ?";
    }

    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(fullSql)) {

      int p = 1;
      // Today branch
      ps.setDate(p++, todaySql); // cd_rem date
      ps.setDate(p++, todaySql); // cd_add date
      ps.setString(p++, stopId);
      ps.setInt(p++, nowSecs); // lower bound (now)
      if (hasLookahead) {
        ps.setInt(p++, nowSecs + lookaheadMins * 60); // upper bound
      }
      ps.setDate(p++, todaySql); // BETWEEN
      ps.setDate(p++, todaySql); // ISODOW

      // After-midnight branch (before 03:00 only)
      if (addAfterMidnight) {
        Date yesterdaySql = Date.valueOf(today.minusDays(1));
        ps.setDate(p++, yesterdaySql); // cd_rem date
        ps.setDate(p++, yesterdaySql); // cd_add date
        ps.setString(p++, stopId);
        ps.setInt(p++, 86400 + nowSecs); // lower bound (now)
        if (hasLookahead) {
          ps.setInt(p++, 86400 + nowSecs + lookaheadMins * 60);
        }
        ps.setDate(p++, yesterdaySql); // BETWEEN
        ps.setDate(p++, yesterdaySql); // ISODOW
      }

      ps.setInt(p++, limit);

      return executeAndMap(ps);
    }
  }

  // ---- Whole-day ----

  private List<ScheduledDeparture> queryWholeDay(String stopId, LocalDate date, int numRows)
      throws SQLException {

    Date dateSql = Date.valueOf(date);
    Date prevDaySql = Date.valueOf(date.minusDays(1));

    String fullSql =
        "("
            + WHOLE_DAY_SQL
            + ")\nUNION ALL\n("
            + WHOLE_DAY_AFTER_MIDNIGHT_SQL
            + ")\nORDER BY departure_secs"
            + (numRows > 0 ? "\nLIMIT ?" : "");

    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(fullSql)) {

      int p = 1;
      // Today's services
      ps.setDate(p++, dateSql); // cd_rem
      ps.setDate(p++, dateSql); // cd_add
      ps.setString(p++, stopId);
      ps.setDate(p++, dateSql); // BETWEEN
      ps.setDate(p++, dateSql); // ISODOW
      // Previous day after-midnight
      ps.setDate(p++, prevDaySql); // cd_rem
      ps.setDate(p++, prevDaySql); // cd_add
      ps.setString(p++, stopId);
      ps.setDate(p++, prevDaySql); // BETWEEN
      ps.setDate(p++, prevDaySql); // ISODOW
      if (numRows > 0) {
        ps.setInt(p++, numRows);
      }

      return executeAndMap(ps);
    }
  }

  // ---- Row mapping ----

  private static List<ScheduledDeparture> executeAndMap(PreparedStatement ps) throws SQLException {
    List<ScheduledDeparture> out = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(mapRow(rs));
      }
    }
    return out;
  }

  private static ScheduledDeparture mapRow(ResultSet rs) throws SQLException {
    int depSecs = rs.getInt("departure_secs");
    // departure_secs may exceed 86400 for after-midnight services; modulo wraps display correctly.
    String scheduledTime = LocalTime.ofSecondOfDay(depSecs % 86400L).format(HH_MM);
    return new ScheduledDeparture(
        scheduledTime,
        rs.getString("line"),
        rs.getString("destination"),
        rs.getString("operator"),
        rs.getString("trip_id"));
  }

  // ---- Utility ----

  private static LocalDate parseDate(String date) {
    if (date == null || date.isBlank()) return null;
    try {
      return LocalDate.parse(date.trim());
    } catch (Exception e) {
      return null;
    }
  }
}
