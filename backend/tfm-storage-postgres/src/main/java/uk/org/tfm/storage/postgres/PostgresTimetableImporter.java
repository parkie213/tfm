package uk.org.tfm.storage.postgres;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.timetable.RowReader;
import uk.org.tfm.model.timetable.TimetableSource;

/**
 * Replaces the GTFS tables with a supplied timetable in one transaction, streaming each table
 * straight from its source into PostgreSQL COPY. Memory use is bounded by one COPY buffer, not by
 * the feed: the East Midlands feed alone is ~21M shape points and ~4.6M stop times.
 */
final class PostgresTimetableImporter {
  private static final Logger log = LoggerFactory.getLogger(PostgresTimetableImporter.class);

  /** Characters buffered before each write to the COPY stream. */
  private static final int FLUSH_CHARS = 64 * 1024;

  private final DataSource dataSource;

  PostgresTimetableImporter(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  void replace(TimetableSource source) throws SQLException, FeedException {
    long startMs = System.currentTimeMillis();
    log.info("Streaming GTFS into Postgres (single transaction)...");

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try {
        try (Statement st = conn.createStatement()) {
          // Truncate in reverse-dependency order to avoid FK issues if any are added later.
          // gtfs_shapes is always truncated so stale geometry is never left behind.
          st.execute(
              "TRUNCATE gtfs_calendar_dates, gtfs_calendar, gtfs_stop_times, "
                  + "gtfs_trips, gtfs_routes, gtfs_agency, gtfs_shapes");
        }

        CopyManager cm = conn.unwrap(PGConnection.class).getCopyAPI();

        copy(
            cm,
            "gtfs_agency (agency_id, agency_name)",
            source.agency(),
            (sb, r) -> {
              field(sb, r.id()).append(',');
              field(sb, r.name());
            });
        copy(
            cm,
            "gtfs_routes (route_id, agency_id, route_short_name, route_long_name, route_color)",
            source.routes(),
            (sb, r) -> {
              field(sb, r.id()).append(',');
              field(sb, r.agencyId()).append(',');
              field(sb, r.shortName()).append(',');
              field(sb, r.longName()).append(',');
              field(sb, r.color());
            });
        copy(
            cm,
            "gtfs_trips (trip_id, route_id, service_id, trip_headsign, shape_id)",
            source.trips(),
            (sb, r) -> {
              field(sb, r.tripId()).append(',');
              field(sb, r.routeId()).append(',');
              field(sb, r.serviceId()).append(',');
              field(sb, r.headsign()).append(',');
              field(sb, r.shapeId());
            });
        copy(
            cm,
            "gtfs_stop_times (trip_id, stop_id, stop_sequence, departure_secs)",
            source.stopTimes(),
            (sb, r) -> {
              field(sb, r.tripId()).append(',');
              field(sb, r.stopId()).append(',');
              sb.append(r.stopSequence()).append(',').append(r.departureSecs());
            });
        copy(
            cm,
            "gtfs_calendar (service_id, monday, tuesday, wednesday, thursday, friday, saturday,"
                + " sunday, start_date, end_date)",
            source.calendar(),
            (sb, r) -> {
              field(sb, r.serviceId()).append(',');
              sb.append(r.monday()).append(',');
              sb.append(r.tuesday()).append(',');
              sb.append(r.wednesday()).append(',');
              sb.append(r.thursday()).append(',');
              sb.append(r.friday()).append(',');
              sb.append(r.saturday()).append(',');
              sb.append(r.sunday()).append(',');
              field(sb, r.startDate()).append(',');
              field(sb, r.endDate());
            });
        copy(
            cm,
            "gtfs_calendar_dates (service_id, exception_date, exception_type)",
            source.calendarDates(),
            (sb, r) -> {
              field(sb, r.serviceId()).append(',');
              field(sb, r.exceptionDate()).append(',');
              sb.append(r.exceptionType());
            });
        copy(
            cm,
            "gtfs_shapes (shape_id, seq, lat, lng)",
            source.shapes(),
            (sb, r) -> {
              field(sb, r.shapeId()).append(',');
              sb.append(r.seq()).append(',').append(r.lat()).append(',').append(r.lng());
            });

        conn.commit();
        log.info("GTFS transaction committed in {}ms.", System.currentTimeMillis() - startMs);
      } catch (Exception e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(true);
      }
    }

    // ANALYZE on a separate connection — must run after commit, outside the load transaction.
    analyzeGtfsTables();
    // NB: the derived route geometry (published shapes, road snapping, and shared segments) is
    // rebuilt by the geometry gate in load(), not here — so it can be skipped when only the feed
    // (not the geometry) changed.
  }

  // ---- Streaming COPY ----

  /** Appends one row's CSV fields (without the trailing newline) to the buffer. */
  @FunctionalInterface
  private interface CsvRow<T> {
    void append(StringBuilder sb, T row);
  }

  /**
   * Streams every row of {@code rows} into {@code COPY <target> FROM STDIN}, flushing a bounded
   * buffer as it goes. Closes {@code rows}; cancels the COPY if anything fails part-way.
   */
  private static <T> void copy(CopyManager cm, String target, RowReader<T> rows, CsvRow<T> csv)
      throws SQLException, FeedException {
    long startMs = System.currentTimeMillis();
    try (rows) {
      CopyIn copy = cm.copyIn("COPY " + target + " FROM STDIN WITH (FORMAT CSV)");
      try {
        StringBuilder sb = new StringBuilder(FLUSH_CHARS + 1024);
        long count = 0;
        for (T row; (row = rows.next()) != null; count++) {
          csv.append(sb, row);
          sb.append('\n');
          if (sb.length() >= FLUSH_CHARS) flush(copy, sb);
        }
        flush(copy, sb);
        copy.endCopy();
        log.info(
            "Copied {} rows into {} in {}ms.",
            count,
            target.substring(0, target.indexOf(' ')),
            System.currentTimeMillis() - startMs);
      } finally {
        if (copy.isActive()) copy.cancelCopy();
      }
    }
  }

  private static void flush(CopyIn copy, StringBuilder sb) throws SQLException {
    if (sb.isEmpty()) return;
    byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
    copy.writeToCopy(bytes, 0, bytes.length);
    sb.setLength(0);
  }

  // ---- ANALYZE ----

  private void analyzeGtfsTables() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {
      st.execute(
          "ANALYZE gtfs_agency, gtfs_routes, gtfs_trips, "
              + "gtfs_stop_times, gtfs_calendar, gtfs_calendar_dates, gtfs_shapes");
      log.info("ANALYZE on GTFS tables complete.");
    }
  }

  /** Appends a double-quoted CSV field, doubling any embedded double-quotes. */
  private static StringBuilder field(StringBuilder sb, String value) {
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"') sb.append('"');
      sb.append(c);
    }
    return sb.append('"');
  }
}
