package uk.org.tfm.server.bods;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.vehicle.VehiclePosition;
import uk.org.tfm.model.vehicle.VehiclePositionProvider;
import uk.org.tfm.server.config.Config;

class BusVehiclePollerTest {
  private static class Feed implements VehiclePositionProvider {
    FeedException failure;
    List<VehiclePosition> vehicles = List.of();

    public List<VehiclePosition> fetch() throws FeedException {
      if (failure != null) throw failure;
      return vehicles;
    }
  }

  private static FeedException unavailable() {
    return new FeedException("unavailable", FeedException.Kind.UNAVAILABLE, Optional.empty());
  }

  @Test
  void backsOffToCapAndSuccessfulFetchResetsRetryDelay() {
    var feed = new Feed();
    var state = new BusVehicleService(Config.withBodsKey("test"));
    var poller =
        new BusVehiclePoller(
            feed, new BusVehicleProcessor(null), state, Config.withBodsKey("test"));
    try {
      feed.failure = unavailable();
      assertEquals(20, poller.pollOnce());
      assertEquals(40, poller.pollOnce());
      assertEquals(80, poller.pollOnce());
      assertEquals(160, poller.pollOnce());
      assertEquals(160, poller.pollOnce());
      feed.failure = null;
      assertEquals(10, poller.pollOnce());
      assertTrue(state.lastFetchedAt().isPresent());
      feed.failure = unavailable();
      assertEquals(20, poller.pollOnce());
    } finally {
      poller.stop();
    }
  }

  @Test
  void rateLimitHonoursRetryAfterAndKeepsLastGoodSnapshot() {
    var feed = new Feed();
    var state = new BusVehicleService(Config.withBodsKey("test"));
    var poller =
        new BusVehiclePoller(
            feed, new BusVehicleProcessor(null), state, Config.withBodsKey("test"));
    try {
      feed.vehicles = List.of(BusVehicleServiceTest.vehicle("1", "A", 52.5, -1.2, Instant.now()));
      poller.pollOnce();
      var before = state.currentResponse();
      feed.failure =
          new FeedException(
              "rate limited", FeedException.Kind.RATE_LIMITED, Optional.of(Duration.ofSeconds(90)));
      assertEquals(90, poller.pollOnce());
      assertEquals(before.getGeneratedAt(), state.currentResponse().getGeneratedAt());
      assertEquals(before.getVehiclesList(), state.currentResponse().getVehiclesList());
      feed.failure =
          new FeedException(
              "rate limited", FeedException.Kind.RATE_LIMITED, Optional.of(Duration.ofSeconds(5)));
      assertEquals(60, poller.pollOnce());
    } finally {
      poller.stop();
    }
  }
}
