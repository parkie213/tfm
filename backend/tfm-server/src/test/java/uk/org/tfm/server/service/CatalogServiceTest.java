package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.catalog.*;
import uk.org.tfm.model.storage.*;

class CatalogServiceTest {
  private static Stop stop(String name) {
    return new Stop(name, name, "", "Town", "BCT", 52.5, -1.2, "");
  }

  private static class Stops implements StopStore {
    int queries;
    List<RankedStop> hits = List.of();

    public List<RankedStop> search(String query, int limit) {
      queries++;
      return hits;
    }

    public StopPage inViewport(double a, double b, double c, double d) {
      return new StopPage(List.of(stop("Stop")), true);
    }

    public void bulkLoad(List<StopDefinition> rows) {}

    public long activeStopCount() {
      return 0;
    }
  }

  private static class Places implements LocalityStore {
    int queries, limit;
    List<RankedPlace> hits = List.of();

    public List<RankedPlace> search(String query, int limit) {
      queries++;
      this.limit = limit;
      return hits;
    }

    public void bulkLoad(List<LocalityDefinition> rows) {}

    public long localityCount() {
      return 0;
    }
  }

  @Test
  void interleavesPlacesAndStopsByTierRankAndScoreBeforeCapping() throws Exception {
    var stops = new Stops();
    var places = new Places();
    stops.hits =
        List.of(new RankedStop(stop("Bus"), 1, 1, 0.99), new RankedStop(stop("Rail"), 3, 2, 0.8));
    places.hits =
        List.of(
            new RankedPlace(new Locality("strong", "Town", "", 52.5, -1.2), 4, 0.9),
            new RankedPlace(new Locality("weak", "Village", "", 52.6, -1.3), 0, 0.99));
    var result = new CatalogService(stops, places).search("Town", 3);
    assertEquals(3, result.getItemsCount());
    assertEquals("Town", result.getItems(0).getPlace().getName());
    assertEquals("Rail", result.getItems(1).getStop().getName());
    assertEquals("Bus", result.getItems(2).getStop().getName());
    assertEquals(3, places.limit);
  }

  @Test
  void rankBucketPrecedesScoreForStopsInSameTier() throws Exception {
    var stops = new Stops();
    stops.hits =
        List.of(
            new RankedStop(stop("Low rank"), 2, 0, 0.99),
            new RankedStop(stop("High rank"), 2, 1, 0.1),
            new RankedStop(stop("Higher score"), 2, 1, 0.9));
    var result = new CatalogService(stops, new Places()).search("Town", 2);
    assertEquals(
        List.of("Higher score", "High rank"),
        result.getItemsList().stream().map(item -> item.getStop().getName()).toList());
  }

  @Test
  void shortSearchDoesNotQueryStorage() throws Exception {
    var stops = new Stops();
    var places = new Places();
    assertEquals(0, new CatalogService(stops, places).search("x", 10).getItemsCount());
    assertEquals(0, stops.queries);
    assertEquals(0, places.queries);
  }

  @Test
  void viewportRetainsTruncationAndCoordinates() throws Exception {
    var result = new CatalogService(new Stops(), new Places()).stopsInViewport(52, -2, 53, -1);
    assertTrue(result.getTruncated());
    assertEquals(52.5, result.getStops(0).getLat());
    assertEquals(-1.2, result.getStops(0).getLng());
  }
}
