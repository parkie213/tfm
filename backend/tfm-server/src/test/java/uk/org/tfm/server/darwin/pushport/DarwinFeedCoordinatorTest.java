package uk.org.tfm.server.darwin.pushport;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.rail.*;
import uk.org.tfm.model.storage.StationPlatformStore;

class DarwinFeedCoordinatorTest {
  private static final TrainScheduleDetails RECORD =
      new TrainScheduleDetails(
          new TrainSchedule("rid", "uid", "1F77", "2026-10-28", "EM", "A", "B", "10:00", "11:00"),
          List.of(new TrainCallingPoint("rid", 1, "A", "", "10:00")));

  private static class Feed implements TrainEventFeed {
    TrainEventSink sink;
    int starts, stops;

    public void start(TrainEventSink sink) {
      this.sink = sink;
      starts++;
    }

    public void stop() {
      stops++;
    }
  }

  private static class Store extends StubTrainScheduleStore {
    List<TrainScheduleDetails> saved = List.of();

    public void upsertLiveBatch(List<TrainScheduleDetails> records) {
      saved = List.copyOf(records);
    }
  }

  private static DarwinPlatformService platforms() {
    return new DarwinPlatformService(
        new PlatformIndex(),
        new StubTrainScheduleStore(),
        new StationPlatformStore() {
          public Map<String, Set<String>> loadAll() {
            return Map.of("A", Set.of("1", "2"));
          }

          public void upsertBatch(Map<String, Set<String>> labels, Instant at) {}
        },
        new PlatformScope());
  }

  @Test
  void scheduleOnlyEventsArePersistedOnShutdownAndMarkFeedFresh() {
    var feed = new Feed();
    var store = new Store();
    var coordinator =
        new DarwinFeedCoordinator(true, feed, new DarwinScheduleFeedService(store), platforms());
    try {
      coordinator.start();
      coordinator.start();
      assertEquals(1, feed.starts);
      assertTrue(coordinator.isRunning());
      feed.sink.onScheduleDetails(RECORD);
      assertTrue(coordinator.lastMessageAt().isPresent());
    } finally {
      coordinator.stop();
    }
    assertEquals(List.of(RECORD), store.saved);
    assertEquals(1, feed.stops);
    assertFalse(coordinator.isRunning());
  }

  @Test
  void scheduleImmediatelyCorrelatesPlatformBeforeDatabaseFlush() {
    var feed = new Feed();
    var platforms = platforms();
    var coordinator =
        new DarwinFeedCoordinator(
            true, feed, new DarwinScheduleFeedService(new Store()), platforms);
    try {
      coordinator.start();
      feed.sink.onScheduleDetails(RECORD);
      feed.sink.onPlatformUpdate(
          new PlatformUpdate("rid", "A", "1", true, false, "P", Instant.now()));
      var occupant = platforms.stationBoard("A").getFirst().trains().getFirst();
      assertEquals("1F77", occupant.headcode());
      feed.sink.onMovement(
          new MovementSnapshot("rid", "A", "10:00", "", "", "", "", "", Instant.now()));
      assertTrue(platforms.stationBoard("A").getFirst().trains().getFirst().atPlatformNow());
    } finally {
      coordinator.stop();
    }
  }

  @Test
  void disabledFeedStillSeedsPlatformInventory() {
    var feed = new Feed();
    var platforms = platforms();
    var coordinator =
        new DarwinFeedCoordinator(
            false, feed, new DarwinScheduleFeedService(new Store()), platforms);
    try {
      coordinator.start();
      assertFalse(coordinator.isRunning());
      assertFalse(coordinator.isEnabled());
      assertEquals(0, feed.starts);
      assertEquals(2, platforms.stationBoard("A").size());
      assertEquals(Optional.empty(), coordinator.lastMessageAt());
    } finally {
      coordinator.stop();
    }
  }
}
