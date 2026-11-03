package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import uk.org.tfm.model.catalog.StopDefinition;
import uk.org.tfm.storage.postgres.PostgresStopStore;

/** Exercises real geography filtering and ordering, independently of the live application DB. */
@Testcontainers(disabledWithoutDocker = true)
class NearbyStopQueryTest {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

  private static PostgresStopStore store;

  @BeforeAll
  static void seed() throws Exception {
    var ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    store = new PostgresStopStore(ds);
    var rows = new ArrayList<StopDefinition>();
    // More than the old viewport cap, with higher-ranked rail stops ahead of the closest bus.
    for (int i = 0; i < 505; i++) rows.add(stop("rail" + i, "RLY", 52.774, -0.88));
    rows.add(stop("A", "BCT", 52.7701, -0.88));
    rows.add(stop("B", "BCT", 52.7701, -0.88));
    rows.add(stop("corner", "BCT", 52.776, -0.870));
    rows.add(stop("inactive", "BCT", 52.77, -0.88));
    rows.add(stop("unsupported", "AIR", 52.77, -0.88));
    store.bulkLoad(rows);
    try (var conn = ds.getConnection();
        var statement = conn.createStatement()) {
      statement.execute("UPDATE stops SET status='inactive' WHERE atco_code='inactive'");
    }
  }

  private static StopDefinition stop(String id, String type, double lat, double lng) {
    return new StopDefinition(id, "Stop " + id, "", "Melton", "", lat, lng, type, "");
  }

  @Test
  void nearestResultsAreDistanceOrderedBeforeCapAndTiesAreStable() throws Exception {
    var page = store.nearby(52.77, -0.88, 800, 2, false);
    assertEquals(List.of("A", "B"), page.stops().stream().map(s -> s.stop().atcoCode()).toList());
    assertTrue(page.truncated());
    assertTrue(page.stops().getFirst().distanceM() > 10);
    assertTrue(page.stops().getFirst().distanceM() < 12);
  }

  @Test
  void radiusAndBusFilterApplyBeforeLimitAndTruncation() throws Exception {
    var page = store.nearby(52.77, -0.88, 800, 2, true);
    assertEquals(List.of("A", "B"), page.stops().stream().map(s -> s.stop().atcoCode()).toList());
    assertFalse(
        page.truncated()); // corner is inside the old bounding square but outside the circle
    var single = store.nearby(52.77, -0.88, 800, 1, true);
    assertEquals("A", single.stops().getFirst().stop().atcoCode());
    assertTrue(single.truncated());
  }

  @Test
  void preciseRadiusExcludesOutsideStopsAndEmptyAreasReturnNoResults() throws Exception {
    double distance = store.nearby(52.77, -0.88, 800, 1, true).stops().getFirst().distanceM();
    assertTrue(store.nearby(52.77, -0.88, distance - 0.001, 10, true).stops().isEmpty());
    assertEquals(2, store.nearby(52.77, -0.88, distance + 0.001, 10, true).stops().size());
    assertTrue(store.nearby(0, 0, 800, 10, false).stops().isEmpty());
  }
}
