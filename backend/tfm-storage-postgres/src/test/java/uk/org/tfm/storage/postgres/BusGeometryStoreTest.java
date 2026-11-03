package uk.org.tfm.storage.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.timetable.*;

class BusGeometryStoreTest {
  @Test
  void shapelessPatternsReturnOrderedWgs84Points() throws Exception {
    var jdbc = new Jdbc();
    jdbc.query =
        sql ->
            List.of(
                Map.of(
                    "route_short_name",
                    "5A",
                    "route_long_name",
                    "Town",
                    "pattern",
                    "hash",
                    "coords",
                    "-1.2,52.5;-1.3,52.6"));
    var routes = new PostgresBusGeometryStore(jdbc.source()).shapelessRoutes();
    assertEquals(
        new BusRoutePattern(
            "5A",
            "Town",
            "hash",
            List.of(new RoutePoint(52.5, -1.2), new RoutePoint(52.6, -1.3)),
            List.of()),
        routes.getFirst());
  }

  @Test
  void bundlingQueryBindsLongitudeBeforeLatitudeAndParsesShape() throws Exception {
    var jdbc = new Jdbc();
    jdbc.query =
        sql ->
            List.of(
                Map.of(
                    "route_short_name",
                    "5A",
                    "route_long_name",
                    "Town",
                    "pattern",
                    "hash",
                    "coords",
                    "-1.2,52.5;-1.3,52.6",
                    "shape_wkt",
                    "LINESTRING(-1.21 52.51,-1.31\t52.61)"));
    var routes =
        new PostgresBusGeometryStore(jdbc.source()).routesForBundling(52.4, -1.4, 52.7, -1.1);
    assertEquals(Map.of(1, -1.4, 2, 52.4, 3, -1.1, 4, 52.7), jdbc.bindings);
    assertEquals(
        List.of(new RoutePoint(52.51, -1.21), new RoutePoint(52.61, -1.31)),
        routes.getFirst().shape());
  }

  @Test
  void publishedShapeReplacementRollsBackOnFailure() {
    var jdbc = new Jdbc();
    jdbc.failUpdate = true;
    var error =
        assertThrows(
            StorageException.class,
            () -> new PostgresBusGeometryStore(jdbc.source()).rebuildPublishedShapes());
    assertInstanceOf(SQLException.class, error.getCause());
    assertEquals(0, jdbc.commits);
    assertEquals(1, jdbc.rollbacks);
    assertEquals(List.of(false, true), jdbc.autoCommit);
    assertTrue(jdbc.statements.contains("DELETE FROM gtfs_route_shapes WHERE source = 'gtfs'"));
  }

  @Test
  void publishedShapeReplacementCommitsBeforeAnalyze() throws Exception {
    var jdbc = new Jdbc();
    new PostgresBusGeometryStore(jdbc.source()).rebuildPublishedShapes();
    assertEquals(1, jdbc.commits);
    assertEquals(0, jdbc.rollbacks);
    assertEquals(2, jdbc.connections);
    assertEquals("ANALYZE gtfs_route_shapes", jdbc.statements.getLast());
  }

  @Test
  void snappedRouteWritesLongitudeLatitudeAndReportsCollapsedGeometry() throws Exception {
    var jdbc = new Jdbc();
    jdbc.updateCount = 0;
    boolean saved =
        new PostgresBusGeometryStore(jdbc.source())
            .saveSnappedRoute(
                new BusRouteShape(
                    "id",
                    "5A",
                    "Town",
                    List.of(new RoutePoint(52.5, -1.2), new RoutePoint(52.6, -1.3))));
    assertFalse(saved);
    assertEquals("LINESTRING(-1.2 52.5,-1.3 52.6)", jdbc.bindings.get(4));
    assertTrue(jdbc.statements.getFirst().contains("ST_NPoints(g) >= 2"));
  }

  @Test
  void retainingPatternsDeletesStaleIdsAndReturnsReusableOnes() throws Exception {
    var jdbc = new Jdbc();
    jdbc.query = sql -> List.of(Map.of(1, "osrm:v2:hash"));
    var ids =
        new PostgresBusGeometryStore(jdbc.source()).retainSnappedRoutes(Set.of("osrm:v2:hash"));
    assertEquals(Set.of("osrm:v2:hash"), ids);
    assertArrayEquals(new String[] {"osrm:v2:hash"}, (Object[]) jdbc.arrays.getFirst());
    assertTrue(jdbc.statements.getFirst().contains("shape_id <> ALL (?)"));
  }

  private static SharedRoadSegment segment() {
    return new SharedRoadSegment(
        List.of(new RoutePoint(52.5, -1.2), new RoutePoint(52.6, -1.3)),
        List.of(
            new BusRouteLabel("5A", "Town", "Operator", "#abcdef"),
            new BusRouteLabel("19", "Village", "Other", "#123456")));
  }

  @Test
  void stagedSegmentsPreserveParallelRouteMetadataAndDoNotPublishLiveTables() throws Exception {
    var jdbc = new Jdbc();
    new PostgresBusGeometryStore(jdbc.source()).stageTrunks(List.of(segment()), 120000);
    assertEquals(1, jdbc.commits);
    assertEquals(0, jdbc.rollbacks);
    assertEquals(4, jdbc.arrays.size());
    assertArrayEquals(new String[] {"5A", "19"}, (Object[]) jdbc.arrays.get(0));
    assertArrayEquals(new String[] {"Town", "Village"}, (Object[]) jdbc.arrays.get(1));
    assertArrayEquals(new String[] {"Operator", "Other"}, (Object[]) jdbc.arrays.get(2));
    assertArrayEquals(new String[] {"#abcdef", "#123456"}, (Object[]) jdbc.arrays.get(3));
    assertTrue(
        jdbc.statements.stream().noneMatch(sql -> sql.equals("DROP TABLE gtfs_route_trunks")));
    assertEquals(120000L, jdbc.bindings.get(1));
  }

  @Test
  void failedStagingRollsBackAndPreservesLiveGeneration() {
    var jdbc = new Jdbc();
    jdbc.failUpdate = true;
    assertThrows(
        StorageException.class,
        () -> new PostgresBusGeometryStore(jdbc.source()).stageTrunks(List.of(segment()), 120000));
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
    int updateCount = 1;

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
                yield updateCount;
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
                case "executeUpdate" -> {
                  String sql = (String) values[0];
                  statements.add(sql);
                  if (failUpdate) throw new SQLException("write failed");
                  yield updateCount;
                }
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
