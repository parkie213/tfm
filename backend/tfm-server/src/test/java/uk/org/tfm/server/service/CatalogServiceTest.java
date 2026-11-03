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
    double radius;
    int nearbyLimit;
    boolean buses;

    public NearbyStopPage nearby(
        double lat, double lng, double radius, int limit, boolean busOnly) {
      this.radius = radius;
      this.nearbyLimit = limit;
      this.buses = busOnly;
      return new NearbyStopPage(List.of(new NearbyStop(stop("Near"), 42)), false);
    }

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

  @Test
  void nearbyDefaultsAndReturnedDistancesAreServerOwned() throws Exception {
    var store = new Stops();
    var result = new CatalogService(store, new Places()).nearby(52.77, -0.88, 0, 0, true);
    assertEquals(800, store.radius);
    assertEquals(100, store.nearbyLimit);
    assertTrue(store.buses);
    assertEquals(42, result.getStops(0).getDistanceM());
    assertEquals("Near", result.getStops(0).getStop().getAtcoCode());
  }

  @Test
  void invalidNearbyInputsAreRejectedBeforeStorage() {
    var service = new CatalogService(new Stops(), new Places());
    for (double lat : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -91, 91})
      assertThrows(IllegalArgumentException.class, () -> service.nearby(lat, 0, 800, 8, false));
    for (double lng : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, -181, 181})
      assertThrows(IllegalArgumentException.class, () -> service.nearby(0, lng, 800, 8, false));
    for (double radius : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 5001})
      assertThrows(IllegalArgumentException.class, () -> service.nearby(0, 0, radius, 8, false));
    for (int limit : new int[] {-1, 501})
      assertThrows(IllegalArgumentException.class, () -> service.nearby(0, 0, 800, limit, false));
  }
}
