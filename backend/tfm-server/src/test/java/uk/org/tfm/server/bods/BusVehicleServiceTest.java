package uk.org.tfm.server.bods;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.vehicle.VehiclePosition;
import uk.org.tfm.server.bods.BusVehicleProcessor.EnrichedVehicle;
import uk.org.tfm.server.bods.BusVehicleProcessor.Snapshot;
import uk.org.tfm.server.config.Config;

class BusVehicleServiceTest {
  static VehiclePosition vehicle(String id, String operator, double lat, double lng, Instant at) {
    return new VehiclePosition(id, "5A", "Town", operator, lat, lng, 90, at, "", -1, "");
  }

  private static Snapshot snapshot(List<EnrichedVehicle> vehicles, Instant at) {
    return new Snapshot(vehicles, Map.of(), Map.of(), at);
  }

  @Test
  void initialSnapshotIsExplicitlyStaleWithoutTimestamp() {
    try (var service = new BusVehicleService(Config.withBodsKey("test"))) {
      var response = service.currentResponse();
      assertTrue(response.getStale());
      assertEquals("", response.getGeneratedAt());
      assertEquals(0, response.getVehiclesCount());
      assertTrue(service.lastFetchedAt().isEmpty());
    }
  }

  @Test
  void keepsFreshestFixPerOperatorScopedVehicle() {
    Instant now = Instant.now();
    try (var service = new BusVehicleService(Config.withBodsKey("test"))) {
      service.publish(
          snapshot(
              List.of(
                  new EnrichedVehicle(vehicle("1", "A", 52.5, -1.2, now.minusSeconds(20)), "old"),
                  new EnrichedVehicle(vehicle("1", "A", 52.6, -1.3, now.minusSeconds(10)), "new"),
                  new EnrichedVehicle(
                      vehicle("1", "B", 52.7, -1.4, now.minusSeconds(10)), "other")),
              now));
      var response = service.currentResponse();
      assertEquals(2, response.getVehiclesCount());
      assertEquals(
          "new",
          response.getVehiclesList().stream()
              .filter(v -> v.getOperator().equals("A"))
              .findFirst()
              .orElseThrow()
              .getTripId());
    }
  }

  @Test
  void trackedTripRemainsVisibleOutsideViewportButOldFixesAreDropped() {
    Instant now = Instant.now();
    try (var service = new BusVehicleService(Config.withBodsKey("test"))) {
      service.publish(
          snapshot(
              List.of(
                  new EnrichedVehicle(vehicle("inside", "A", 52.5, -1.2, now), "inside"),
                  new EnrichedVehicle(vehicle("tracked", "A", 54, -3, now), "tracked"),
                  new EnrichedVehicle(vehicle("outside", "A", 54, -3, now), "outside"),
                  new EnrichedVehicle(
                      vehicle("old", "A", 52.5, -1.2, now.minusSeconds(600)), "old")),
              now));
      var response = service.currentResponse(52, -2, 53, -1, "tracked");
      assertEquals(
          Set.of("inside", "tracked"),
          response.getVehiclesList().stream().map(v -> v.getId()).collect(Collectors.toSet()));
      assertFalse(response.getStale());
    }
  }

  @Test
  void oldSnapshotIsMarkedStaleWithoutReplacingItsTimestamp() {
    Instant fetched = Instant.now().minusSeconds(600);
    try (var service = new BusVehicleService(Config.withBodsKey("test"))) {
      service.publish(snapshot(List.of(), fetched));
      assertTrue(service.currentResponse().getStale());
      assertEquals(fetched.toString(), service.currentResponse().getGeneratedAt());
    }
  }
}
