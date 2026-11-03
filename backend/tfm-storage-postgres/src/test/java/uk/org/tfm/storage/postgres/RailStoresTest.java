package uk.org.tfm.storage.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.sql.Array;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.rail.*;
import uk.org.tfm.model.storage.StorageException;

class RailStoresTest {
  private static final ScheduleRecord RECORD =
      new ScheduleRecord(
          new TrainSchedule("rid", "uid", "1F77", "2026-10-28", "EM", "A", "B", "10:00", "11:00"),
          List.of(new TrainCallingPoint("rid", 1, "A", "", "10:00")));

  @Test
  void callingPointsPreserveSequenceAndTimes() {
    var jdbc = new Jdbc();
    jdbc.query =
        sql -> List.of(Map.of("rid", "rid", "seq", 2, "tpl", "B", "arr", "11:00:30", "dep", ""));
    var stops = new PostgresTrainScheduleStore(jdbc.source()).callingPoints("rid");
    assertEquals(List.of(new TrainCallingPoint("rid", 2, "B", "11:00:30", "")), stops);
    assertEquals("rid", jdbc.bindings.get(1));
    assertTrue(jdbc.statements.getFirst().contains("ORDER BY seq"));
  }

  @Test
  void liveScheduleHeaderAndReplacementStopsShareTransaction() throws Exception {
    var jdbc = new Jdbc();
    new PostgresTrainScheduleStore(jdbc.source()).upsertLiveBatch(List.of(RECORD));
    assertEquals(1, jdbc.connections);
    assertEquals(1, jdbc.commits);
    assertEquals(0, jdbc.rollbacks);
    assertTrue(jdbc.statements.getFirst().startsWith("DELETE FROM darwin_schedule_stops"));
    assertTrue(
        jdbc.statements.stream().anyMatch(sql -> sql.contains("ON CONFLICT (rid) DO UPDATE")));
    assertArrayEquals(new String[] {"rid"}, (Object[]) jdbc.arrays.getFirst());
  }

  @Test
  void failedScheduleWriteTranslatesErrorAndRollsBack() {
    var jdbc = new Jdbc();
    jdbc.failUpdate = true;
    var failure =
        assertThrows(
            StorageException.class,
            () -> new PostgresTrainScheduleStore(jdbc.source()).upsertLiveBatch(List.of(RECORD)));
    assertInstanceOf(SQLException.class, failure.getCause());
    assertEquals(1, jdbc.rollbacks);
    assertEquals(0, jdbc.commits);
  }

  @Test
  void platformInventoryMapsLabelsAndWritesObservationTime() throws Exception {
    var jdbc = new Jdbc();
    jdbc.query = sql -> List.of(Map.of(1, "A", 2, "1"), Map.of(1, "A", 2, "2"));
    var store = new PostgresStationPlatformStore(jdbc.source());
    assertEquals(Map.of("A", Set.of("1", "2")), store.loadAll());
    Instant at = Instant.parse("2026-10-28T10:00:00Z");
    store.upsertBatch(Map.of("A", Set.of("1")), at);
    assertEquals(Timestamp.from(at), jdbc.bindings.get(3));
    assertEquals(1, jdbc.commits);
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
              case "addBatch" -> null;
              case "executeBatch" -> {
                if (failUpdate) throw new SQLException("batch failed");
                yield new int[] {1};
              }
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
