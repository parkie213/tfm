package uk.org.tfm.adapter.gtfs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.timetable.*;

class GtfsClientTest {
  @TempDir Path cache;

  private static Map<String, String> entries(String suffix) {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("agency.txt", "\uFEFFagency_name,agency_id\n\"Bus, Company\",A" + suffix + "\n");
    files.put(
        "routes.txt",
        "route_id,agency_id,route_short_name,route_long_name,route_color\n"
            + "R"
            + suffix
            + ",A"
            + suffix
            + ",19,Melton to Oakham,FF0000\n");
    files.put(
        "trips.txt",
        "trip_id,route_id,service_id,trip_headsign,shape_id\n"
            + "T"
            + suffix
            + ",R"
            + suffix
            + ",S,Oakham,H\n");
    files.put(
        "stop_times.txt",
        "departure_time,stop_sequence,stop_id,trip_id\n"
            + "25:03:00,2,stop-2,T"
            + suffix
            + "\n08:15:00,1,stop-1,T"
            + suffix
            + "\n");
    files.put(
        "calendar.txt",
        "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
            + "S,1,1,1,1,1,0,0,20261001,20261231\n");
    files.put("calendar_dates.txt", "service_id,date,exception_type\nS,20261225,2\nS,20261226,1\n");
    files.put(
        "shapes.txt",
        "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n"
            + "H,52.77,-0.88,1\nH,52.78,-0.87,2\n");
    return files;
  }

  private Path zip(String region, Map<String, String> files) throws Exception {
    Path path = cache.resolve(region + "-gtfs.zip");
    try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var file : files.entrySet()) {
        out.putNextEntry(new ZipEntry(file.getKey()));
        out.write(file.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return path;
  }

  /** Every table of a source, fully read — the parser's whole output for assertions. */
  private record Tables(
      List<Agency> agency,
      List<Route> routes,
      List<Journey> trips,
      List<StopTime> stopTimes,
      List<ServiceCalendar> calendar,
      List<CalendarException> calendarDates,
      List<ShapePoint> shapes) {}

  private static Tables drain(TimetableSource source) throws FeedException {
    return new Tables(
        rows(source.agency()),
        rows(source.routes()),
        rows(source.trips()),
        rows(source.stopTimes()),
        rows(source.calendar()),
        rows(source.calendarDates()),
        rows(source.shapes()));
  }

  private static <T> List<T> rows(RowReader<T> reader) throws FeedException {
    try (reader) {
      List<T> out = new ArrayList<>();
      for (T row; (row = reader.next()) != null; ) out.add(row);
      return out;
    }
  }

  @Test
  void cachedArchiveProducesTypedTimetableWithServiceDayTimesAndSuppliedShapes() throws Exception {
    zip("east_midlands", entries(""));
    var timetable = drain(new GtfsClient(cache, "east_midlands", true).fetch().read());
    assertEquals("Bus, Company", timetable.agency().getFirst().name());
    assertEquals("A", timetable.agency().getFirst().id());
    assertEquals("19", timetable.routes().getFirst().shortName());
    assertEquals("FF0000", timetable.routes().getFirst().color());
    assertEquals("Oakham", timetable.trips().getFirst().headsign());
    assertEquals("R", timetable.trips().getFirst().routeId());
    assertEquals("S", timetable.trips().getFirst().serviceId());
    assertEquals("H", timetable.trips().getFirst().shapeId());
    assertEquals(25 * 3600 + 3 * 60, timetable.stopTimes().getFirst().departureSecs());
    assertEquals("stop-2", timetable.stopTimes().getFirst().stopId());
    assertEquals(2, timetable.stopTimes().getFirst().stopSequence());
    assertTrue(timetable.calendar().getFirst().monday());
    assertFalse(timetable.calendar().getFirst().sunday());
    assertEquals("2026-10-01", timetable.calendar().getFirst().startDate());
    assertEquals("2026-12-31", timetable.calendar().getFirst().endDate());
    assertEquals("2026-12-25", timetable.calendarDates().getFirst().exceptionDate());
    assertEquals(2, timetable.calendarDates().getFirst().exceptionType());
    assertEquals(2, timetable.shapes().size());
    assertEquals(52.77, timetable.shapes().getFirst().lat());
    assertEquals(-0.88, timetable.shapes().getFirst().lng());
  }

  @Test
  void optionalCalendarsShapesAndColumnsCanBeAbsent() throws Exception {
    var files = entries("");
    files.remove("calendar.txt");
    files.remove("calendar_dates.txt");
    files.remove("shapes.txt");
    files.put("routes.txt", "route_id,agency_id,route_short_name,route_long_name\nR,A,19,Melton\n");
    files.put("trips.txt", "trip_id,route_id,service_id,trip_headsign\nT,R,S,Melton\n");
    zip("east_midlands", files);
    var timetable = drain(new GtfsClient(cache, "", true).fetch().read());
    assertTrue(timetable.calendar().isEmpty());
    assertTrue(timetable.calendarDates().isEmpty());
    assertTrue(timetable.shapes().isEmpty());
    assertEquals("", timetable.routes().getFirst().color());
    assertEquals("", timetable.trips().getFirst().shapeId());
  }

  @Test
  void shapesCanBeDisabledWithoutDroppingJourneyShapeIds() throws Exception {
    zip("east_midlands", entries(""));
    var timetable = drain(new GtfsClient(cache, "east_midlands", false).fetch().read());
    assertTrue(timetable.shapes().isEmpty());
    assertEquals("H", timetable.trips().getFirst().shapeId());
  }

  @Test
  void combinedVersionMatchesTheExistingOrderedArchiveFingerprint() throws Exception {
    Path first = zip("east_midlands", entries("1"));
    Path second = zip("london", entries("2"));
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(Files.readAllBytes(first));
    digest.update(Files.readAllBytes(second));
    var client = new GtfsClient(cache, " east_midlands, london ", false);
    var snapshot = client.fetch();
    assertEquals(HexFormat.of().formatHex(digest.digest()), snapshot.version());
    assertEquals(snapshot.version(), client.fetch().version());
    assertEquals(2, drain(snapshot.read()).trips().size());
    assertNotEquals(
        snapshot.version(), new GtfsClient(cache, "london,east_midlands", false).fetch().version());
  }

  @Test
  void preparingARevisionDoesNotDecodeTheArchiveUntilRead() throws Exception {
    zip("east_midlands", Map.of("agency.txt", "wrong,header\n"));
    var snapshot = new GtfsClient(cache, "east_midlands", true).fetch();
    assertEquals(64, snapshot.version().length());
    var error = assertThrows(FeedException.class, () -> drain(snapshot.read()));
    assertEquals(FeedException.Kind.INVALID_RESPONSE, error.kind());
  }

  @Test
  void missingRequiredTableIsAParseFailureRatherThanAnEmptyTimetable() throws Exception {
    var files = entries("");
    files.remove("stop_times.txt");
    zip("east_midlands", files);
    var snapshot = new GtfsClient(cache, "east_midlands", true).fetch();
    assertEquals(
        FeedException.Kind.INVALID_RESPONSE,
        assertThrows(FeedException.class, () -> drain(snapshot.read())).kind());
  }

  @Test
  void malformedNumericRowsAreSkippedWithoutLosingValidRows() throws Exception {
    var files = entries("");
    files.merge("stop_times.txt", "bad,3,stop-3,T\n08:00:00,invalid,stop-4,T\n", String::concat);
    files.merge("shapes.txt", "H,invalid,-0.88,3\n", String::concat);
    files.merge("calendar_dates.txt", "S,invalid,1\n", String::concat);
    zip("east_midlands", files);
    var timetable = drain(new GtfsClient(cache, "east_midlands", true).fetch().read());
    assertEquals(2, timetable.stopTimes().size());
    assertEquals(2, timetable.shapes().size());
    assertEquals(2, timetable.calendarDates().size());
  }
}
