package uk.org.tfm.storage.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.storage.StorageException;

class CatalogStoresTest {
  private static Map<String, Object> stop(String type) {
    return Map.of(
        "atco_code",
        "stop-1",
        "common_name",
        "Melton station",
        "indicator",
        "entrance",
        "locality_name",
        "Melton Mowbray",
        "stop_type",
        type,
        "crs",
        "MMO",
        "lat",
        52.76,
        "lng",
        -0.88,
        "stop_rank",
        10,
        "score",
        0.9);
  }

  @Test
  void viewportMapsModelValuesPreservesCoordinateOrderAndReportsTruncation() throws Exception {
    var jdbc = new Jdbc();
    jdbc.rows = Collections.nCopies(501, stop("RLY"));
    var page = new PostgresStopStore(jdbc.source()).inViewport(52, -1, 53, 0);
    assertTrue(page.truncated());
    assertEquals(500, page.stops().size());
    var stop = page.stops().getFirst();
    assertEquals("stop-1", stop.atcoCode());
    assertEquals("Melton station", stop.name());
    assertEquals("entrance", stop.indicator());
    assertEquals("Melton Mowbray", stop.locality());
    assertEquals("RLY", stop.stopType());
    assertEquals("MMO", stop.crs());
    assertEquals(52.76, stop.lat());
    assertEquals(-0.88, stop.lng());
    assertEquals(Map.of(1, -1.0, 2, 52.0, 3, 0.0, 4, 53.0, 5, 501), jdbc.bindings);
  }

  @Test
  void stopSearchReturnsRankingKeysAndClampsLimitWithinTransaction() throws Exception {
    var jdbc = new Jdbc();
    jdbc.rows = List.of(stop("RLY"), stop("MET"), stop("BCT"));
    var hits = new PostgresStopStore(jdbc.source()).search(" Melton ", 500);
    assertEquals(List.of(2, 1, 0), hits.stream().map(h -> h.tier()).toList());
    assertEquals(10, hits.getFirst().rankBucket());
    assertEquals(0.9, hits.getFirst().score());
    assertEquals("melton", jdbc.bindings.get(1));
    assertEquals(50, jdbc.bindings.get(4));
    assertEquals(1, jdbc.commits);
    assertEquals(0, jdbc.rollbacks);
    assertEquals(List.of(false, true), jdbc.autoCommit);
  }

  @Test
  void localitySearchMapsWgs84AndPreservesStrongVersusWeakRanking() throws Exception {
    var jdbc = new Jdbc();
    jdbc.rows =
        List.of(
            Map.of(
                "code",
                "place-1",
                "name",
                "Melton",
                "qualifier",
                "Leicestershire",
                "lat",
                52.77,
                "lng",
                -0.88,
                "ws",
                0.9,
                "dist_m",
                15000.0),
            Map.of(
                "code",
                "place-2",
                "name",
                "Near match",
                "qualifier",
                "",
                "lat",
                52.78,
                "lng",
                -0.87,
                "ws",
                0.7,
                "dist_m",
                30000.0));
    var hits = new PostgresLocalityStore(jdbc.source()).search("Melton", 100);
    assertEquals("place-1", hits.getFirst().place().code());
    assertEquals("Leicestershire", hits.getFirst().place().qualifier());
    assertEquals(52.77, hits.getFirst().place().lat());
    assertEquals(-0.88, hits.getFirst().place().lng());
    assertEquals(3, hits.getFirst().tier());
    assertEquals(0.8, hits.getFirst().score(), 0.00001);
    assertEquals(0, hits.get(1).tier());
    assertEquals(0.5, hits.get(1).score(), 0.00001);
    assertEquals(10, jdbc.bindings.get(4));
    assertEquals(1, jdbc.commits);
  }

  @Test
  void failedSearchRollsBackAndTranslatesDriverFailure() {
    var jdbc = new Jdbc();
    jdbc.failure = new SQLException("database unavailable");
    var error =
        assertThrows(
            StorageException.class, () -> new PostgresStopStore(jdbc.source()).search("melton", 0));
    assertSame(jdbc.failure, error.getCause());
    assertEquals(1, jdbc.rollbacks);
    assertEquals(0, jdbc.commits);
    assertEquals(List.of(false, true), jdbc.autoCommit);
  }

  @Test
  void localityFailureAlsoRollsBackAndCountErrorsUseTheStorageContract() {
    var jdbc = new Jdbc();
    jdbc.failure = new SQLException("database unavailable");
    assertThrows(
        StorageException.class, () -> new PostgresLocalityStore(jdbc.source()).search("melton", 0));
    assertEquals(1, jdbc.rollbacks);
    assertThrows(
        StorageException.class, () -> new PostgresLocalityStore(jdbc.source()).localityCount());
    assertThrows(
        StorageException.class, () -> new PostgresStopStore(jdbc.source()).activeStopCount());
  }

  /** JDBC protocol stub: exercises mappings, binds and transaction handling without a database. */
  private static final class Jdbc {
    List<Map<String, Object>> rows = List.of();
    final Map<Integer, Object> bindings = new HashMap<>();
    final List<Boolean> autoCommit = new ArrayList<>();
    int commits;
    int rollbacks;
    SQLException failure;

    DataSource source() {
      return proxy(
          DataSource.class,
          (method, args) ->
              switch (method) {
                case "getConnection" -> connection();
                default -> throw new UnsupportedOperationException(method);
              });
    }

    private Connection connection() {
      return proxy(
          Connection.class,
          (method, args) ->
              switch (method) {
                case "prepareStatement" -> statement();
                case "createStatement" ->
                    proxy(
                        Statement.class,
                        (name, values) ->
                            switch (name) {
                              case "execute" -> true;
                              case "close" -> null;
                              default -> throw new UnsupportedOperationException(name);
                            });
                case "setAutoCommit" -> {
                  autoCommit.add((Boolean) args[0]);
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
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method);
              });
    }

    private PreparedStatement statement() {
      return proxy(
          PreparedStatement.class,
          (method, args) ->
              switch (method) {
                case "setString", "setInt", "setDouble" -> {
                  bindings.put((Integer) args[0], args[1]);
                  yield null;
                }
                case "executeQuery" -> {
                  if (failure != null) throw failure;
                  yield result();
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method);
              });
    }

    private ResultSet result() {
      int[] index = {-1};
      return proxy(
          ResultSet.class,
          (method, args) ->
              switch (method) {
                case "next" -> ++index[0] < rows.size();
                case "getString", "getInt", "getDouble", "getLong" ->
                    rows.get(index[0]).get(args[0]);
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method);
              });
    }
  }

  @FunctionalInterface
  private interface Invocation {
    Object call(String method, Object[] args) throws Throwable;
  }

  private static <T> T proxy(Class<T> type, Invocation invocation) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> invocation.call(method.getName(), args)));
  }
}
