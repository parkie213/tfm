package uk.org.tfm.server.gtfs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import uk.org.tfm.adapter.gtfs.GtfsClient;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.timetable.StopTime;
import uk.org.tfm.storage.postgres.PostgresTimetableStore;

/**
 * End-to-end GTFS import against real PostGIS: fixture archives → {@link GtfsClient} → {@link
 * PostgresTimetableStore#bulkLoad} → every {@code gtfs_*} table, compared with a committed golden
 * dump. The golden was captured from the list-materialising importer, so this pins the exact rows
 * any reimplementation (e.g. streaming) must reproduce.
 *
 * <p>To regenerate after an intentional change: run with {@code -Dgtfs.golden.update=true} and
 * review the diff of {@code gtfs-import-golden.txt}.
 */
@Testcontainers(disabledWithoutDocker = true)
class GtfsImportRoundTripTest {

  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

  private static final List<String> TABLES =
      List.of(
          "gtfs_agency",
          "gtfs_routes",
          "gtfs_trips",
          "gtfs_stop_times",
          "gtfs_calendar",
          "gtfs_calendar_dates",
          "gtfs_shapes");

  private static final Path GOLDEN =
      Path.of("src/test/resources/gtfs/gtfs-import-golden.txt").toAbsolutePath();

  private static DataSource db;

  @TempDir Path cache;

  @BeforeAll
  static void migrate() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    db = ds;
  }

  // ---- Fixtures ----

  /**
   * Region one exercises the parser's edge cases: BOM, reordered and extra columns, quoting with
   * commas and doubled quotes, CRLF line endings, whitespace, after-midnight times, and one
   * malformed row per numeric/date field (each must be skipped without losing its neighbours).
   */
  private static Map<String, String> regionOne() {
    Map<String, String> f = new LinkedHashMap<>();
    f.put(
        "agency.txt",
        "﻿agency_url,agency_name,agency_id\r\n"
            + "http://a.example,\"Bus, Company \"\"Ltd\"\"\",A1\r\n"
            + "http://b.example,  Spacey Buses  , A2 \r\n");
    f.put(
        "routes.txt",
        "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\n"
            + "R1,A1,19,\"Melton, Market Place to Oakham\",3,FF0000\n"
            + "R2,A2,\"5A\",,3,\n");
    f.put(
        "trips.txt",
        "route_id,service_id,trip_id,trip_headsign,shape_id,direction_id\n"
            + "R1,S1,T1,\"Oakham, Bus Station\",H1,0\n"
            + "R1,S1,T2,Oakham,H1,0\n"
            + "R2,S2,T3,\"Says \"\"Hello\"\"\",,1\n");
    f.put(
        "stop_times.txt",
        "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type\n"
            + "T1,08:00:00,08:00:00,stop-1,1,0\n"
            + "T1,08:10:00,08:10:30,stop-2,2,0\n"
            + "T2,23:50:00,23:55:00,stop-1,1,0\n"
            + "T2,24:10:00,25:03:00,stop-2,2,0\n"
            + "T3,07:00:00,7:05:00,stop-3,1,0\n"
            + "T3,07:10:00,bad,stop-4,2,0\n"
            + "T3,07:20:00,07:20:00,stop-5,not-a-number,0\n"
            + "T3,07:30:00,,stop-6,4,0\n"
            + "short,row\n"
            + " T3 ,07:40:00, 07:40:00 , stop-7 , 5 ,0\n");
    f.put(
        "calendar.txt",
        "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
            + "S1,1,1,1,1,1,0,0,20261001,20261231\n"
            + "S2,0,0,0,0,0,1,1,20261001,20270331\n"
            + "S3,1,1,1,1,1,1,1,2026-10-01,20261231\n");
    f.put(
        "calendar_dates.txt",
        "service_id,date,exception_type\n"
            + "S1,20261225,2\n"
            + "S2,20261226,1\n"
            + "S1,not-a-date,1\n"
            + "S1,20261227,x\n");
    f.put(
        "shapes.txt",
        "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence,shape_dist_traveled\n"
            + "H1,52.7661,-0.8856,1,0\n"
            + "H1,52.77,-0.88,2,500\n"
            + "H1,invalid,-0.87,3,900\n"
            + "H1,52.6700,-0.7300,4,15000.5\n");
    return f;
  }

  /** Region two: minimal feed with no optional files and no optional columns. */
  private static Map<String, String> regionTwo() {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("agency.txt", "agency_id,agency_name\nB1,Second Region Buses\n");
    f.put("routes.txt", "route_id,agency_id,route_short_name,route_long_name\nR9,B1,X9,Express\n");
    f.put("trips.txt", "trip_id,route_id,service_id,trip_headsign\nT9,R9,S9,Leicester\n");
    f.put(
        "stop_times.txt",
        "trip_id,stop_id,stop_sequence,departure_time\n"
            + "T9,stop-9a,1,06:00:00\n"
            + "T9,stop-9b,2,06:45:00\n");
    return f;
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

  private void load(String regions, boolean shapes) throws Exception {
    new PostgresTimetableStore(db).bulkLoad(new GtfsClient(cache, regions, shapes).fetch().read());
  }

  // ---- Tests ----

  @Test
  void importedTablesMatchGolden() throws Exception {
    zip("one", regionOne());
    zip("two", regionTwo());
    load("one,two", true);

    String actual = dumpTables();
    if (Boolean.getBoolean("gtfs.golden.update")) {
      Files.createDirectories(GOLDEN.getParent());
      Files.writeString(GOLDEN, actual);
    }
    assertTrue(Files.exists(GOLDEN), "golden missing; run with -Dgtfs.golden.update=true");
    assertEquals(Files.readString(GOLDEN), actual);
  }

  @Test
  void reloadReplacesPreviousTimetableEntirely() throws Exception {
    zip("one", regionOne());
    load("one", true);
    zip("two", regionTwo());
    load("two", true);

    assertEquals(List.of("B1"), column("SELECT agency_id FROM gtfs_agency"));
    assertEquals(List.of("T9"), column("SELECT trip_id FROM gtfs_trips"));
    assertEquals(0, count("gtfs_shapes"), "a feed without shapes must not keep stale geometry");
    assertEquals(0, count("gtfs_calendar"));
  }

  @Test
  void shapesDisabledKeepsTripShapeIdsButLoadsNoGeometry() throws Exception {
    zip("one", regionOne());
    load("one", false);

    assertEquals(0, count("gtfs_shapes"));
    assertEquals(List.of("H1"), column("SELECT shape_id FROM gtfs_trips WHERE trip_id = 'T1'"));
  }

  @Test
  void brokenFeedLeavesPreviousTimetableInPlace() throws Exception {
    zip("one", regionOne());
    load("one", true);
    String before = dumpTables();

    Map<String, String> broken = regionOne();
    broken.put("stop_times.txt", "trip_id,stop_id,departure_time\nT1,stop-1,08:00:00\n");
    zip("one", broken);
    assertThrows(Exception.class, () -> load("one", true));

    assertEquals(before, dumpTables(), "a failed load must roll back to the previous timetable");
  }

  @Test
  void largeTableStreamsAcrossManyBufferFlushes() throws Exception {
    int rows = 50_000; // ~2 MB of CSV: dozens of 64 KB flushes
    new PostgresTimetableStore(db).bulkLoad(new SyntheticTimetable(rows, -1));

    long expectedSum = 0;
    for (int i = 0; i < rows; i++) expectedSum += SyntheticTimetable.row(i).departureSecs();
    assertEquals(rows, count("gtfs_stop_times"));
    assertEquals(
        List.of(String.valueOf(expectedSum)),
        column("SELECT sum(departure_secs) FROM gtfs_stop_times"));
    StopTime last = SyntheticTimetable.row(rows - 1);
    assertEquals(
        List.of(last.tripId() + "|" + last.stopId() + "|" + last.stopSequence()),
        column(
            "SELECT trip_id || '|' || stop_id || '|' || stop_sequence FROM gtfs_stop_times"
                + " WHERE departure_secs = "
                + last.departureSecs()
                + " AND trip_id = '"
                + last.tripId()
                + "'"));
  }

  @Test
  void failurePartWayThroughACopyRollsBackToThePreviousTimetable() throws Exception {
    zip("one", regionOne());
    load("one", true);
    String before = dumpTables();

    var store = new PostgresTimetableStore(db);
    var error =
        assertThrows(
            FeedException.class, () -> store.bulkLoad(new SyntheticTimetable(50_000, 30_000)));
    assertTrue(error.getMessage().contains("30000"));

    assertEquals(before, dumpTables(), "a mid-COPY failure must roll back the whole load");
  }

  /**
   * Imports 2M stop times in a child JVM capped at 128 MB. Materialising them (the old importer)
   * needs roughly 270 MB, so this fails if the load ever collects a table into memory again.
   */
  @Test
  void importRunsInConstantMemory() throws Exception {
    int rows = 2_000_000;
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-XX:+ExitOnOutOfMemoryError",
                "-cp",
                System.getProperty("java.class.path"),
                SyntheticTimetable.class.getName(),
                PG.getJdbcUrl(),
                PG.getUsername(),
                PG.getPassword(),
                String.valueOf(rows))
            .redirectErrorStream(true)
            .start();
    String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(child.waitFor(3, TimeUnit.MINUTES), "child import timed out");
    assertEquals(0, child.exitValue(), "child import failed:\n" + output);
    assertEquals(rows, count("gtfs_stop_times"));
  }

  // ---- Helpers ----

  /** Every gtfs_* table, rows sorted, as tab-separated text (stable across load order). */
  private static String dumpTables() throws Exception {
    StringBuilder out = new StringBuilder();
    try (Connection c = db.getConnection();
        Statement st = c.createStatement()) {
      for (String table : TABLES) {
        out.append("== ").append(table).append('\n');
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
          ResultSetMetaData md = rs.getMetaData();
          StringBuilder header = new StringBuilder();
          for (int i = 1; i <= md.getColumnCount(); i++) {
            header.append(i > 1 ? "\t" : "").append(md.getColumnName(i));
          }
          out.append(header).append('\n');
          while (rs.next()) {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= md.getColumnCount(); i++) {
              row.append(i > 1 ? "\t" : "").append(rs.getString(i));
            }
            rows.add(row.toString());
          }
        }
        rows.sort(null);
        rows.forEach(r -> out.append(r).append('\n'));
      }
    }
    return out.toString();
  }

  private static List<String> column(String sql) throws Exception {
    List<String> values = new ArrayList<>();
    try (Connection c = db.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) values.add(rs.getString(1));
    }
    return values;
  }

  private static int count(String table) throws Exception {
    try (Connection c = db.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
