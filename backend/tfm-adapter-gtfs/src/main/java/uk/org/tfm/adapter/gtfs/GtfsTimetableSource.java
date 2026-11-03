package uk.org.tfm.adapter.gtfs;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipFile;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.timetable.Agency;
import uk.org.tfm.model.timetable.CalendarException;
import uk.org.tfm.model.timetable.Journey;
import uk.org.tfm.model.timetable.Route;
import uk.org.tfm.model.timetable.RowReader;
import uk.org.tfm.model.timetable.ServiceCalendar;
import uk.org.tfm.model.timetable.ShapePoint;
import uk.org.tfm.model.timetable.StopTime;
import uk.org.tfm.model.timetable.TimetableSource;

/**
 * Decodes GTFS archives into model rows, one table at a time. Each table is streamed line by line
 * across every region's archive in order, so nothing beyond the current line is held in memory.
 *
 * <p>Required tables (agency, routes, trips, stop_times) must exist in every archive; a missing
 * table or required column fails the read. Rows too short for the required columns, or with an
 * unparseable number, time, or date, are skipped.
 */
final class GtfsTimetableSource implements TimetableSource {
  private static final DateTimeFormatter GTFS_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final List<Path> zips;
  private final boolean loadShapes;

  GtfsTimetableSource(List<Path> zips, boolean loadShapes) {
    this.zips = List.copyOf(zips);
    this.loadShapes = loadShapes;
  }

  @Override
  public RowReader<Agency> agency() throws FeedException {
    return table(
        "agency.txt",
        true,
        cols("agency_id", "agency_name"),
        cols(),
        (f, c, o) -> new Agency(f[c[0]].trim(), GtfsLoader.unquote(f[c[1]])));
  }

  @Override
  public RowReader<Route> routes() throws FeedException {
    return table(
        "routes.txt",
        true,
        cols("route_id", "agency_id", "route_short_name", "route_long_name"),
        cols("route_color"), // optional in the GTFS spec
        (f, c, o) ->
            new Route(
                f[c[0]].trim(),
                f[c[1]].trim(),
                GtfsLoader.unquote(f[c[2]]),
                GtfsLoader.unquote(f[c[3]]),
                optional(f, o[0], true)));
  }

  @Override
  public RowReader<Journey> trips() throws FeedException {
    return table(
        "trips.txt",
        true,
        cols("trip_id", "route_id", "service_id", "trip_headsign"),
        cols("shape_id"), // absent in older archives
        (f, c, o) ->
            new Journey(
                f[c[0]].trim(),
                f[c[1]].trim(),
                f[c[2]].trim(),
                GtfsLoader.unquote(f[c[3]]),
                optional(f, o[0], false)));
  }

  @Override
  public RowReader<StopTime> stopTimes() throws FeedException {
    return table(
        "stop_times.txt",
        true,
        cols("trip_id", "stop_id", "stop_sequence", "departure_time"),
        cols(),
        (f, c, o) -> {
          int depSecs = GtfsLoader.parseGtfsTime(f[c[3]].trim());
          if (depSecs < 0) return null;
          Integer seq = parseInt(f[c[2]]);
          if (seq == null) return null;
          return new StopTime(f[c[0]].trim(), f[c[1]].trim(), seq, depSecs);
        });
  }

  @Override
  public RowReader<ServiceCalendar> calendar() throws FeedException {
    return table(
        "calendar.txt",
        false,
        cols(
            "service_id",
            "monday",
            "tuesday",
            "wednesday",
            "thursday",
            "friday",
            "saturday",
            "sunday",
            "start_date",
            "end_date"),
        cols(),
        (f, c, o) -> {
          String startDate = parseGtfsDateToSql(f[c[8]].trim());
          String endDate = parseGtfsDateToSql(f[c[9]].trim());
          if (startDate == null || endDate == null) return null;
          return new ServiceCalendar(
              f[c[0]].trim(),
              "1".equals(f[c[1]].trim()),
              "1".equals(f[c[2]].trim()),
              "1".equals(f[c[3]].trim()),
              "1".equals(f[c[4]].trim()),
              "1".equals(f[c[5]].trim()),
              "1".equals(f[c[6]].trim()),
              "1".equals(f[c[7]].trim()),
              startDate,
              endDate);
        });
  }

  @Override
  public RowReader<CalendarException> calendarDates() throws FeedException {
    return table(
        "calendar_dates.txt",
        false,
        cols("service_id", "date", "exception_type"),
        cols(),
        (f, c, o) -> {
          String exDate = parseGtfsDateToSql(f[c[1]].trim());
          if (exDate == null) return null;
          Integer exType = parseInt(f[c[2]]);
          if (exType == null) return null;
          return new CalendarException(f[c[0]].trim(), exDate, exType);
        });
  }

  @Override
  public RowReader<ShapePoint> shapes() throws FeedException {
    if (!loadShapes) return RowReader.empty();
    return table(
        "shapes.txt",
        false,
        cols("shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence"),
        cols(),
        (f, c, o) -> {
          try {
            double lat = Double.parseDouble(f[c[1]].trim());
            double lng = Double.parseDouble(f[c[2]].trim());
            int seq = Integer.parseInt(f[c[3]].trim());
            return new ShapePoint(f[c[0]].trim(), seq, lat, lng);
          } catch (NumberFormatException e) {
            return null;
          }
        });
  }

  // ---- Table reading ----

  /** Maps one CSV row to a model row, or {@code null} to skip it as malformed. */
  @FunctionalInterface
  private interface RowMapper<T> {
    T map(String[] fields, int[] required, int[] optional);
  }

  private static String[] cols(String... names) {
    return names;
  }

  private <T> RowReader<T> table(
      String entry, boolean required, String[] columns, String[] optional, RowMapper<T> mapper)
      throws FeedException {
    CsvTableReader<T> reader =
        new CsvTableReader<>(zips, entry, required, columns, optional, mapper);
    reader.advanceRegion(); // fail fast on a structurally invalid first archive
    return reader;
  }

  /**
   * Streams one table across all region archives in order. Holds at most one open archive and one
   * buffered line; header indices are resolved per archive since column order may differ.
   */
  private static final class CsvTableReader<T> implements RowReader<T> {
    private final List<Path> zips;
    private final String entry;
    private final boolean required;
    private final String[] columns;
    private final String[] optional;
    private final RowMapper<T> mapper;

    private int nextZip;
    private ZipFile zip;
    private BufferedReader lines;
    private int[] idx;
    private int[] optIdx;
    private int maxIdx;

    CsvTableReader(
        List<Path> zips,
        String entry,
        boolean required,
        String[] columns,
        String[] optional,
        RowMapper<T> mapper) {
      this.zips = zips;
      this.entry = entry;
      this.required = required;
      this.columns = columns;
      this.optional = optional;
      this.mapper = mapper;
    }

    @Override
    public T next() throws FeedException {
      try {
        while (lines != null) {
          String line = lines.readLine();
          if (line == null) {
            advanceRegion();
            continue;
          }
          String[] f = GtfsLoader.parseCsvLine(line);
          if (f.length <= maxIdx) continue;
          T row = mapper.map(f, idx, optIdx);
          if (row != null) return row;
        }
        return null;
      } catch (IOException e) {
        throw invalid("GTFS " + entry + " read failed", e);
      }
    }

    /** Closes the current archive and opens the next one that has this table with a header. */
    void advanceRegion() throws FeedException {
      close();
      while (nextZip < zips.size()) {
        Path path = zips.get(nextZip++);
        try {
          zip = new ZipFile(path.toFile());
          if (zip.getEntry(entry) == null) {
            if (required) throw new IOException("GTFS zip missing entry: " + entry);
            close();
            continue;
          }
          lines = GtfsLoader.readerFor(zip, entry);
          String header = lines.readLine();
          if (header == null) { // empty table
            close();
            continue;
          }
          idx = GtfsLoader.indices(header, columns);
          optIdx = new int[optional.length];
          for (int i = 0; i < optional.length; i++) {
            optIdx[i] = GtfsLoader.optionalIndex(header, optional[i]);
          }
          maxIdx = 0;
          for (int i : idx) maxIdx = Math.max(maxIdx, i);
          return;
        } catch (IOException e) {
          close();
          throw invalid("GTFS archive " + path.getFileName() + " invalid", e);
        }
      }
    }

    @Override
    public void close() {
      try {
        if (lines != null) lines.close();
        if (zip != null) zip.close();
      } catch (IOException ignored) {
        // read-only archive; nothing to flush
      } finally {
        lines = null;
        zip = null;
      }
    }
  }

  // ---- Field helpers ----

  /** An optional column's value, or "" when the column or the field is absent. */
  private static String optional(String[] f, int index, boolean unquote) {
    if (index < 0 || index >= f.length) return "";
    return unquote ? GtfsLoader.unquote(f[index]) : f[index].trim();
  }

  private static Integer parseInt(String s) {
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static FeedException invalid(String message, IOException cause) {
    return new FeedException(message, FeedException.Kind.INVALID_RESPONSE, cause);
  }

  private static String parseGtfsDateToSql(String s) {
    if (s == null || s.length() != 8) return null;
    try {
      // Validate by parsing
      LocalDate.parse(s, GTFS_DATE_FMT);
      return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
    } catch (Exception e) {
      return null;
    }
  }
}
