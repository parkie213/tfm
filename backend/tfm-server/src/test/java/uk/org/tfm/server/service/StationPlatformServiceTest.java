package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.board.Board;
import uk.org.tfm.model.board.BoardProvider;
import uk.org.tfm.model.feed.FeedHealth;
import uk.org.tfm.model.feed.TrainBoardFeedHealth;
import uk.org.tfm.proto.v1.*;
import uk.org.tfm.server.darwin.pushport.DarwinPlatformService;
import uk.org.tfm.server.darwin.pushport.DarwinPlatformService.*;
import uk.org.tfm.server.darwin.pushport.PlatformIndex;
import uk.org.tfm.server.darwin.pushport.PlatformScope;

class StationPlatformServiceTest {
  private static StationBoardRow row(String id, String platform, int seconds) {
    return StationBoardRow.newBuilder()
        .setServiceId(id)
        .setPlatform(platform)
        .setOrigin("Cambridge")
        .setDestination("Birmingham")
        .setSortSecs(seconds)
        .setDep(
            StationBoardTime.newBuilder()
                .setScheduled("10:00")
                .setExpected("10:12")
                .setStatus("Delayed"))
        .build();
  }

  private static Occupant push(String rid, boolean present, boolean departed) {
    return new Occupant(rid, "1A01", "Cambridge", "Birmingham", true, present, departed);
  }

  @Test
  void sharedPlatformAndEvenIdenticalIdTextCannotTransferConfirmationOrPresence() {
    var board =
        GetStationBoardResponse.newBuilder()
            .addRows(row("rid1", "1", 1))
            .addRows(row("following", "1", 2))
            .build();
    var result =
        StationPlatformService.reconcile(
            "MMO",
            "Melton",
            board,
            List.of(new PlatformLine("1", List.of(push("rid1", true, false)))),
            true,
            true);
    assertEquals(2, result.getPlatforms(0).getTrainsCount());
    for (var t : result.getPlatforms(0).getTrainsList()) {
      assertTrue(t.hasBoardRow());
      assertFalse(t.getPlatformConfirmed());
      assertFalse(t.getAtPlatformNow());
      assertEquals(PlatformPresence.PLATFORM_PRESENCE_UNKNOWN, t.getPresence());
      assertEquals("10:12", t.getBoardRow().getDep().getExpected());
    }
  }

  @Test
  void conflictingPlatformsRemainIndependentAndUnmatchedKafkaUsesOnlyItsOwnState() {
    var result =
        StationPlatformService.reconcile(
            "MMO",
            "Melton",
            GetStationBoardResponse.newBuilder().addRows(row("rid1", "2", 1)).build(),
            List.of(
                new PlatformLine(
                    "1",
                    List.of(
                        push("rid1", true, false),
                        push("rid2", false, false),
                        push("rid3", true, true)))),
            true,
            true);
    var trains = result.getPlatforms(0).getTrainsList();
    assertEquals(3, trains.size());
    assertEquals(PlatformPresence.PLATFORM_PRESENCE_PRESENT, trains.get(0).getPresence());
    assertEquals(PlatformPresence.PLATFORM_PRESENCE_UNKNOWN, trains.get(1).getPresence());
    assertEquals(PlatformPresence.PLATFORM_PRESENCE_DEPARTED, trains.get(2).getPresence());
    assertFalse(trains.get(2).getAtPlatformNow());
    assertFalse(result.getPlatforms(1).getTrains(0).getPlatformConfirmed());
  }

  @Test
  void missingRestKeepsInventoryAndKafkaFallback() {
    var result =
        StationPlatformService.reconcile(
            "MMO",
            "Melton",
            null,
            List.of(
                new PlatformLine("1", List.of()),
                new PlatformLine("2", List.of(push("rid", true, false)))),
            true,
            true);
    assertFalse(result.getBoardAvailable());
    assertTrue(result.getPlatformDataAvailable());
    assertEquals(0, result.getPlatforms(0).getTrainsCount());
    assertTrue(result.getPlatforms(1).getTrains(0).getAtPlatformNow());
  }

  @Test
  void failedOrDisabledPlatformFeedKeepsSortedRestRowsWithFinalLimit() {
    var board =
        GetStationBoardResponse.newBuilder()
            .addRows(row("late", "3", 3))
            .addRows(row("first", "3", 1))
            .addRows(row("second", "3", 2))
            .addRows(row("unassigned", "", 0))
            .build();
    var result = StationPlatformService.reconcile("MMO", "Melton", board, List.of(), false, false);
    assertTrue(result.getBoardAvailable());
    assertFalse(result.getPlatformFeedEnabled());
    assertFalse(result.getPlatformDataAvailable());
    assertEquals(1, result.getPlatformsCount());
    assertEquals(
        List.of("rest:first", "rest:second"),
        result.getPlatforms(0).getTrainsList().stream()
            .map(PlatformOccupant::getIdentity)
            .toList());
  }

  private static StationPlatformService service(boolean failRest, boolean failPlatforms) {
    var provider =
        new BoardProvider() {
          public Board getDepartureBoard(String crs, int rows) {
            if (failRest) throw new IllegalStateException("REST failed");
            return new Board("Melton", "", "", List.of());
          }

          public Board getArrivalBoard(String crs, int rows) {
            throw new AssertionError("Unconfigured arrivals must not be called");
          }
        };
    var health =
        new FeedHealth() {
          public boolean configured() {
            return false;
          }

          public Optional<Instant> lastSuccessAt() {
            return Optional.empty();
          }

          public Optional<Instant> lastErrorAt() {
            return Optional.empty();
          }

          public Optional<String> lastError() {
            return Optional.empty();
          }
        };
    var scope =
        new PlatformScope() {
          @Override
          public Optional<String> tplForCrs(String crs) {
            if (failPlatforms) throw new IllegalStateException("Platform read failed");
            return Optional.of("MLTON");
          }
        };
    var platforms = new DarwinPlatformService(new PlatformIndex(), null, null, scope);
    var feed =
        new PlatformFeedInfo() {
          public boolean isEnabled() {
            return false;
          }

          public boolean isRunning() {
            return false;
          }

          public int platformIndexSize() {
            return 0;
          }

          public Optional<Instant> lastMessageAt() {
            return Optional.empty();
          }
        };
    return new StationPlatformService(
        platforms,
        feed,
        scope,
        new StationBoardService(
            new TrainBoardService(provider), new TrainBoardFeedHealth(health, health)));
  }

  @Test
  void restExceptionStillReturnsSuccessfulPlatformRead() {
    var result = service(true, false).getPlatforms("MMO");
    assertFalse(result.getBoardAvailable());
    assertTrue(result.getPlatformDataAvailable());
  }

  @Test
  void platformExceptionStillReturnsSuccessfulRestRead() {
    var result = service(false, true).getPlatforms("MMO");
    assertTrue(result.getBoardAvailable());
    assertFalse(result.getPlatformDataAvailable());
  }

  @Test
  void bothSourcesFailRatherThanReturnAnApparentlyEmptySuccessfulBoard() {
    assertThrows(IllegalStateException.class, () -> service(true, true).getPlatforms("MMO"));
  }
}
