package uk.org.tfm.storage.postgres;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import uk.org.tfm.model.timetable.*;

/** Calendar-valid batch matching of live bus journey identities to unique timetable trips. */
final class TimetableTripMatcher {
  private final DataSource dataSource;

  TimetableTripMatcher(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  // ---- Live vehicle → GTFS trip matching ----

  /**
   * Resolves a batch of journey identities to GTFS trip_ids in ONE round trip. Returns an entry
   * only for journeys with exactly one calendar-valid candidate trip; a journey with zero or with
   * more than one candidate is simply absent (the caller treats absence as "no unique match" — we
   * never guess). Mirrors the boards' calendar priority: removal (type 2) beats addition (type 1)
   * beats the base weekly calendar. Replaces ~one-query-per-journey with a single set-at-a-time
   * query.
   */
  Map<JourneyKey, String> queryMatchTripIds(Collection<JourneyKey> journeys) throws SQLException {
    Map<JourneyKey, String> out = new HashMap<>();
    if (journeys == null || journeys.isEmpty()) {
      return out;
    }
    // Parallel arrays for unnest — keeps the SQL text constant regardless of batch size (caches
    // well).
    String[] lines = new String[journeys.size()];
    String[] origins = new String[journeys.size()];
    Integer[] depSecs = new Integer[journeys.size()];
    Date[] dates = new Date[journeys.size()];
    String[] dests = new String[journeys.size()];
    int i = 0;
    for (JourneyKey j : journeys) {
      lines[i] = j.line();
      origins[i] = j.originRef();
      depSecs[i] = j.originDepSecs();
      dates[i] = Date.valueOf(j.serviceDate());
      dests[i] = j.destination() == null ? "" : j.destination();
      i++;
    }
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(MATCH_TRIPS_SQL)) {
      ps.setArray(1, conn.createArrayOf("text", lines));
      ps.setArray(2, conn.createArrayOf("text", origins));
      ps.setArray(3, conn.createArrayOf("int4", depSecs));
      ps.setArray(4, conn.createArrayOf("date", dates));
      ps.setArray(5, conn.createArrayOf("text", dests));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.put(
              new JourneyKey(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getInt(3),
                  rs.getDate(4).toLocalDate(),
                  rs.getString(5)),
              rs.getString(6));
        }
      }
    }
    return out;
  }

  /**
   * Batched trip matcher: join a poll's journeys (as parallel arrays → unnest) to candidate trips
   * with the same calendar predicate as the boards, then pick a trip per journey:
   *
   * <ol>
   *   <li>if exactly one candidate's {@code trip_headsign} matches the live {@code destination},
   *       take it — this disambiguates a reused line's two same-origin/time legs (the 5A bug);
   *   <li>otherwise fall back to the old "refuse to guess" rule: a single distinct candidate, else
   *       none. So a journey that resolves uniquely today is never changed by the headsign step.
   * </ol>
   *
   * Params: (1) lines[], (2) origins[], (3) depSecs[], (4) dates[], (5) destinations[].
   */
  private static final String MATCH_TRIPS_SQL =
      """
      WITH req(line, origin_ref, dep_secs, svc_date, destination) AS (
        SELECT * FROM unnest(?::text[], ?::text[], ?::int[], ?::date[], ?::text[])
      )
      SELECT line, origin_ref, dep_secs, svc_date, destination, trip_id FROM (
        SELECT req.line, req.origin_ref, req.dep_secs, req.svc_date, req.destination,
               CASE
                 WHEN count(DISTINCT t.trip_id) FILTER (WHERE req.destination <> ''
                        AND lower(btrim(t.trip_headsign)) = lower(btrim(req.destination))) = 1
                   THEN min(t.trip_id) FILTER (WHERE req.destination <> ''
                        AND lower(btrim(t.trip_headsign)) = lower(btrim(req.destination)))
                 WHEN count(DISTINCT t.trip_id) = 1 THEN min(t.trip_id)
                 ELSE NULL
               END AS trip_id
        FROM   req
        JOIN   gtfs_routes r ON r.route_short_name = req.line
        JOIN   gtfs_trips  t ON t.route_id = r.route_id
        JOIN   gtfs_stop_times st
               ON st.trip_id = t.trip_id
              AND st.stop_id        = req.origin_ref
              AND st.departure_secs = req.dep_secs
              AND st.stop_sequence  = (SELECT min(y.stop_sequence)
                                       FROM gtfs_stop_times y WHERE y.trip_id = t.trip_id)
        LEFT JOIN gtfs_calendar cal
               ON cal.service_id = t.service_id
        LEFT JOIN gtfs_calendar_dates cd_rem
               ON cd_rem.service_id = t.service_id
              AND cd_rem.exception_date = req.svc_date AND cd_rem.exception_type = 2
        LEFT JOIN gtfs_calendar_dates cd_add
               ON cd_add.service_id = t.service_id
              AND cd_add.exception_date = req.svc_date AND cd_add.exception_type = 1
        WHERE  cd_rem.exception_date IS NULL
          AND  (cd_add.exception_date IS NOT NULL
                OR (cal.service_id IS NOT NULL
                    AND req.svc_date BETWEEN cal.start_date AND cal.end_date
                    AND CASE EXTRACT(ISODOW FROM req.svc_date)
                          WHEN 1 THEN cal.monday WHEN 2 THEN cal.tuesday WHEN 3 THEN cal.wednesday
                          WHEN 4 THEN cal.thursday WHEN 5 THEN cal.friday
                          WHEN 6 THEN cal.saturday WHEN 7 THEN cal.sunday END))
        GROUP BY req.line, req.origin_ref, req.dep_secs, req.svc_date, req.destination
      ) z
      WHERE trip_id IS NOT NULL""";
}
