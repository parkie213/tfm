package uk.org.tfm.server.bods;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.storage.*;
import uk.org.tfm.model.timetable.*;
import uk.org.tfm.model.vehicle.VehiclePosition;
import uk.org.tfm.server.config.Config;
import uk.org.tfm.server.db.OsrmRouter;
import uk.org.tfm.server.service.BusTimetableService;

class BusVehicleProcessorTest {
  private static class Timetables implements TimetableStore {
    int queries, failures;

    public Map<JourneyKey, String> matchTripIds(Collection<JourneyKey> keys)
        throws StorageException {
      queries++;
      if (failures-- > 0)
        throw new StorageException("temporary failure", new IllegalStateException());
      var found = new HashMap<JourneyKey, String>();
      for (var key : keys) found.put(key, "trip-" + key.originDepSecs());
      return found;
    }

    public JourneyDetails getServiceStops(String id) {
      return new JourneyDetails(
          "5A",
          "Town",
          "A",
          List.of(),
          List.of(new RoutePoint(52.5, -1.2), new RoutePoint(52.6, -1.3)));
    }

    public JourneyDetails getRouteStops(String a, String b, String c) {
      return JourneyDetails.EMPTY;
    }

    public ScheduledBoard getDepartures(String id, int rows, int lookahead, String date) {
      return new ScheduledBoard("", "", List.of());
    }

    public boolean hasTimetable() {
      return true;
    }

    public void bulkLoad(TimetableSource source) {}

    public long tripCount() {
      return 0;
    }
  }

  private static VehiclePosition matched(Instant at, int departure) {
    return new VehiclePosition(
        "1", "5A", "Town", "A", 52.5, -1.2, 90, at, "ORIGIN", departure, "2026-10-28");
  }

  private static BusVehicleProcessor processor(Timetables store) {
    return new BusVehicleProcessor(
        new BusTimetableService(store, new OsrmRouter(Config.minimalForTesting())));
  }

  @Test
  void duplicateFixDoesNotBecomePreviousFixAndNextFixRetainsRealHistory() {
    try (var processor = new BusVehicleProcessor(null)) {
      Instant now = Instant.now();
      var first = BusVehicleServiceTest.vehicle("1", "A", 52.5, -1.2, now.minusSeconds(20));
      var next = BusVehicleServiceTest.vehicle("1", "A", 52.6, -1.3, now.minusSeconds(10));
      processor.process(List.of(first));
      assertTrue(processor.process(List.of(first)).prevByVehicle().isEmpty());
      var result = processor.process(List.of(next));
      assertEquals(first, result.prevByVehicle().get(BusVehicleProcessor.vehicleKey(first)));
    }
  }

  @Test
  void tripMatchingIsCachedAndNewJourneyClearsPreviousFixHistory() {
    var store = new Timetables();
    try (var processor = processor(store)) {
      Instant now = Instant.now();
      processor.process(List.of(matched(now.minusSeconds(20), 36000)));
      var second = processor.process(List.of(matched(now.minusSeconds(10), 36000)));
      assertEquals(1, store.queries);
      assertEquals(1, second.prevByVehicle().size());
      var changed = processor.process(List.of(matched(now, 36600)));
      assertEquals(2, store.queries);
      assertTrue(changed.prevByVehicle().isEmpty());
      assertEquals("trip-36600", changed.vehicles().getFirst().tripId());
    }
  }

  @Test
  void storageFailureDoesNotPoisonMatchCache() {
    var store = new Timetables();
    store.failures = 1;
    try (var processor = processor(store)) {
      var vehicle = matched(Instant.now(), 36000);
      assertEquals("", processor.process(List.of(vehicle)).vehicles().getFirst().tripId());
      assertEquals(
          "trip-36000", processor.process(List.of(vehicle)).vehicles().getFirst().tripId());
      processor.process(List.of(vehicle));
      assertEquals(2, store.queries);
    }
  }
}
