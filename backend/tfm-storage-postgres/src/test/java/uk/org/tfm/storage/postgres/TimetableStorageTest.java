package uk.org.tfm.storage.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.sql.Array;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.timetable.*;
import uk.org.tfm.model.timetable.JourneyKey;

class TimetableStorageTest {
  @Test
  void wholeDayBoardMapsAfterMidnightAndKeepsCalendarBindings() throws Exception {
    var jdbc = new Jdbc();
    jdbc.query =
        sql ->
            sql.contains("SELECT common_name")
                ? List.<Map<Object, Object>>of(Map.of(1, "Market Place"))
                : List.<Map<Object, Object>>of(
                    Map.of(
                        "departure_secs",
                        90000,
                        "line",
                        "19",
                        "destination",
                        "Oakham",
                        "operator",
                        "Bus Co",
                        "trip_id",
                        "trip-1"));
    var board =
        new PostgresTimetableStore(jdbc.source()).getDepartures("stop-1", 10, 0, "2026-10-28");
    assertEquals("Market Place", board.stopName());
    var row = board.departures().getFirst();
    assertEquals("01:00", row.scheduledTime());
    assertEquals("19", row.line());
    assertEquals("trip-1", row.tripId());
    assertEquals("Oakham", row.destination());
    assertTrue(jdbc.bindings.containsValue(Date.valueOf("2026-10-28")));
    assertTrue(jdbc.bindings.containsValue(Date.valueOf("2026-10-27")));
    assertTrue(jdbc.statements.stream().anyMatch(s -> s.contains("cd_rem.exception_date IS NULL")));
  }

  @Test
  void journeyQueryKeepsStopsWithUnknownCoordinatesAndReturnsOnlySuppliedShape() throws Exception {
    var jdbc = new Jdbc();
    jdbc.query =
        sql -> {
          if (sql.contains("SELECT r.route_short_name, t.trip_headsign")) {
            return List.<Map<Object, Object>>of(Map.of(1, "19", 2, "Oakham", 3, "Bus Co"));
          }
          if (sql.contains("SELECT st.stop_sequence")) {
            return List.<Map<Object, Object>>of(Map.of(1, 1, 2, 90000, 3, "missing-stop"));
          }
          return List.<Map<Object, Object>>of(Map.of(1, 52.77, 2, -0.88));
        };
    var details = new PostgresTimetableStore(jdbc.source()).getServiceStops("trip-1");
    assertEquals("19", details.line());
    assertEquals("Bus Co", details.operator());
    var stop = details.stops().getFirst();
    assertEquals("missing-stop", stop.name());
    assertEquals("01:00", stop.scheduledTime());
    assertEquals(0, stop.lat());
    assertEquals(0, stop.lng());
    assertEquals(52.77, details.shape().getFirst().lat());
    assertEquals(-0.88, details.shape().getFirst().lng());
  }

  @Test
  void journeyMatcherUsesOneBatchAndNormalizesAbsentDestination() throws Exception {
    var jdbc = new Jdbc();
    jdbc.query =
        sql ->
            List.<Map<Object, Object>>of(
                Map.of(
                    1,
                    "19",
                    2,
                    "origin",
                    3,
                    36000,
                    4,
                    Date.valueOf("2026-10-28"),
                    5,
                    "",
                    6,
                    "trip-1"));
    var key = new JourneyKey("19", "origin", 36000, LocalDate.of(2026, 10, 28), null);
    var matches = new PostgresTimetableStore(jdbc.source()).matchTripIds(List.of(key));
    assertEquals(1, jdbc.connections);
    assertEquals(
        "trip-1", matches.get(new JourneyKey("19", "origin", 36000, key.serviceDate(), "")));
    assertArrayEquals(new String[] {""}, (String[]) jdbc.arrays.getLast());
    assertTrue(jdbc.statements.getFirst().contains("unnest"));
  }

  @Test
  void failedImportRollsBackAndDoesNotCommit() {
    var jdbc = new Jdbc();
    var empty =
        new TimetableSource() {
          public RowReader<Agency> agency() {
            return RowReader.empty();
          }

          public RowReader<Route> routes() {
            return RowReader.empty();
          }

          public RowReader<Journey> trips() {
            return RowReader.empty();
          }

          public RowReader<StopTime> stopTimes() {
            return RowReader.empty();
          }

          public RowReader<ServiceCalendar> calendar() {
            return RowReader.empty();
          }

          public RowReader<CalendarException> calendarDates() {
            return RowReader.empty();
          }

          public RowReader<ShapePoint> shapes() {
            return RowReader.empty();
          }
        };
    var error =
        assertThrows(
            StorageException.class,
            () -> new PostgresTimetableStore(jdbc.source()).bulkLoad(empty));
    assertInstanceOf(SQLException.class, error.getCause());
    assertEquals(1, jdbc.rollbacks);
    assertEquals(0, jdbc.commits);
    assertEquals(List.of(false, true), jdbc.autoCommit);
  }

  @Test
  void missingLoadStateReturnsUnknownFields() throws Exception {
    var state = new PostgresLoadStateStore(new Jdbc().source()).read();
    assertNull(state.feedVersion());
    assertNull(state.feedLoadedAt());
    assertNull(state.geometryKey());
    assertNull(state.geometryBuiltAt());
  }

  @Test
  void geometryPromotionAndFingerprintShareOneCommit() throws Exception {
    var jdbc = new Jdbc();
    jdbc.query = sql -> List.<Map<Object, Object>>of(Map.of(1, true));
    Instant at = Instant.parse("2026-10-28T10:00:00Z");
    new PostgresLoadStateStore(jdbc.source()).publishGeometry("new-generation", at);
    assertEquals(1, jdbc.connections);
    assertEquals(1, jdbc.commits);
    assertEquals(0, jdbc.rollbacks);
    assertFalse(jdbc.statements.stream().anyMatch(sql -> sql.contains("gtfs_route_lanes")));
    assertTrue(jdbc.statements.contains("DROP TABLE gtfs_route_trunks"));
    assertEquals("new-generation", jdbc.bindings.get(1));
    assertEquals(Timestamp.from(at), jdbc.bindings.get(2));
  }

  @Test
  void fingerprintFailureRollsBackGeometryPromotion() {
    var jdbc = new Jdbc();
    jdbc.query = sql -> List.<Map<Object, Object>>of(Map.of(1, true));
    jdbc.failUpdate = true;
    assertThrows(
        StorageException.class,
        () ->
            new PostgresLoadStateStore(jdbc.source())
                .publishGeometry("new-generation", Instant.now()));
    assertEquals(0, jdbc.commits);
    assertEquals(1, jdbc.rollbacks);
    assertEquals(List.of(false, true), jdbc.autoCommit);
  }

  private static final class Jdbc {
    Function<String, List<Map<Object, Object>>> query = sql -> List.of();
    final List<String> statements = new ArrayList<>();
    final Map<Integer, Object> bindings = new HashMap<>();
    final List<Object> arrays = new ArrayList<>();
    final List<Boolean> autoCommit = new ArrayList<>();
    int connections, commits, rollbacks;
    boolean failUpdate;

    DataSource source() {
      return proxy(
          DataSource.class,
          (method, args) -> {
            if (!method.equals("getConnection")) throw new UnsupportedOperationException(method);
            connections++;
            return proxy(
                Connection.class,
                (name, values) ->
                    switch (name) {
                      case "prepareStatement" -> prepared((String) values[0]);
                      case "createStatement" -> statement();
                      case "createArrayOf" -> {
                        Object array = values[1];
                        arrays.add(array);
                        yield proxy(Array.class, (a, b) -> a.equals("getArray") ? array : null);
                      }
                      case "setAutoCommit" -> {
                        autoCommit.add((Boolean) values[0]);
                        yield null;
                      }
                      case "commit" -> {
                        commits++;
                        yield null;
                      }
                      case "rollback" -> {
                        rollbacks++;
                        yield null;
                      }
                      case "unwrap" -> throw new SQLException("COPY connection unavailable");
                      case "close" -> null;
                      default -> throw new UnsupportedOperationException(name);
                    });
          });
    }

    private PreparedStatement prepared(String sql) {
      statements.add(sql);
      return proxy(
          PreparedStatement.class,
          (name, values) -> {
            if (name.startsWith("set")) {
              bindings.put((Integer) values[0], values[1]);
              return null;
            }
            return switch (name) {
              case "executeQuery" -> result(query.apply(sql));
              case "executeUpdate" -> {
                if (failUpdate) throw new SQLException("fingerprint update failed");
                yield 1;
              }
              case "close" -> null;
              default -> throw new UnsupportedOperationException(name);
            };
          });
    }

    private Statement statement() {
      return proxy(
          Statement.class,
          (name, values) ->
              switch (name) {
                case "execute", "executeQuery" -> {
                  String sql = (String) values[0];
                  statements.add(sql);
                  yield name.equals("executeQuery") ? result(query.apply(sql)) : true;
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(name);
              });
    }

    private ResultSet result(List<Map<Object, Object>> rows) {
      int[] index = {-1};
      boolean[] wasNull = {false};
      return proxy(
          ResultSet.class,
          (name, values) -> {
            if (name.equals("next")) return ++index[0] < rows.size();
            if (name.equals("close")) return null;
            if (name.equals("wasNull")) return wasNull[0];
            Object value = rows.get(index[0]).get(values[0]);
            wasNull[0] = value == null;
            return switch (name) {
              case "getString", "getDate", "getTimestamp" -> value;
              case "getInt" -> value == null ? 0 : value;
              case "getDouble" -> value == null ? 0.0 : value;
              case "getBoolean" -> value == null ? false : value;
              default -> throw new UnsupportedOperationException(name);
            };
          });
    }
  }

  @FunctionalInterface
  private interface Call {
    Object invoke(String name, Object[] args) throws Throwable;
  }

  private static <T> T proxy(Class<T> type, Call call) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (instance, method, args) -> call.invoke(method.getName(), args)));
  }
}
