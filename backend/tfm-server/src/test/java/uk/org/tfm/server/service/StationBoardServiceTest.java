package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.board.Board;
import uk.org.tfm.model.board.BoardEntry;
import uk.org.tfm.model.board.BoardProvider;
import uk.org.tfm.model.board.Coach;
import uk.org.tfm.model.feed.FeedHealth;
import uk.org.tfm.model.feed.TrainBoardFeedHealth;

class StationBoardServiceTest {
  private static final class Boards implements BoardProvider {
    List<BoardEntry> departures = List.of();
    List<BoardEntry> arrivals = List.of();
    boolean failDepartures;
    boolean failArrivals;
    int arrivalsCalls;

    public Board getDepartureBoard(String station, int rows) {
      if (failDepartures) throw new IllegalStateException("departures failed");
      return new Board("Melton Mowbray", "2026-11-03T10:00:00Z", "Disruption notice", departures);
    }

    public Board getArrivalBoard(String station, int rows) {
      arrivalsCalls++;
      if (failArrivals) throw new IllegalStateException("arrivals failed");
      return new Board("Melton Mowbray", "2026-11-03T10:00:01Z", "", arrivals);
    }
  }

  private static BoardEntry entry(String id, String time, String destination, String origin) {
    return new BoardEntry(
        id,
        time,
        Optional.of(time),
        destination,
        origin,
        "",
        "",
        BoardEntry.Status.ON_TIME,
        true,
        OptionalInt.of(0),
        0,
        List.of());
  }

  private static FeedHealth health(boolean configured) {
    return new FeedHealth() {
      public boolean configured() {
        return configured;
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
  }

  private static StationBoardService service(Boards boards, boolean arrivalsConfigured) {
    return new StationBoardService(
        new TrainBoardService(boards),
        new TrainBoardFeedHealth(health(true), health(arrivalsConfigured)));
  }

  @Test
  void joinsThroughServicesAndKeepsOriginatingAndTerminatingServices() {
    var boards = new Boards();
    boards.departures =
        List.of(
            entry("through", "10:15", "Birmingham", ""),
            entry("originating", "10:30", "Leicester", ""));
    boards.arrivals =
        List.of(
            entry("through", "10:13", "", "Cambridge"),
            entry("terminating", "10:05", "", "Nottingham"));
    var result = service(boards, true).getBoard("MMO", 15);
    assertEquals(
        List.of("terminating", "through", "originating"),
        result.getRowsList().stream().map(r -> r.getServiceId()).toList());
    var through = result.getRows(1);
    assertEquals("10:13", through.getArr().getScheduled());
    assertEquals("10:15", through.getDep().getScheduled());
    assertEquals("Cambridge", through.getOrigin());
    assertEquals("Birmingham", through.getDestination());
    assertFalse(result.getRows(0).hasDep());
    assertFalse(result.getRows(2).hasArr());
    assertTrue(result.getArrivalsAvailable());
    assertEquals("Melton Mowbray", result.getStationName());
    assertEquals("Disruption notice", result.getNrccMessages());
    assertEquals("2026-11-03T10:00:00Z", result.getGeneratedAt());
  }

  @Test
  void departureDetailsWinConflictsAndArrivalsFillMissingDetails() {
    var boards = new Boards();
    boards.departures =
        List.of(
            new BoardEntry(
                "train",
                "10:15",
                Optional.of("10:22"),
                "Birmingham",
                "",
                "CrossCountry",
                "1",
                BoardEntry.Status.DELAYED,
                false,
                OptionalInt.of(7),
                0,
                List.of()));
    boards.arrivals =
        List.of(
            new BoardEntry(
                "train",
                "10:13",
                Optional.of("10:20"),
                "",
                "Cambridge",
                "Other operator",
                "2",
                BoardEntry.Status.DELAYED,
                true,
                OptionalInt.of(7),
                4,
                List.of(new Coach("A", "Standard", OptionalInt.empty(), ""))));
    var row = service(boards, true).getBoard("MMO", 15).getRows(0);
    assertEquals("1", row.getPlatform());
    assertEquals("CrossCountry", row.getOperator());
    assertEquals("Cambridge", row.getOrigin());
    assertTrue(row.getIsLive());
    assertEquals(4, row.getCoachCount());
    assertEquals("A", row.getCoaches(0).getNumber());
    assertEquals("10:22", row.getDep().getExpected());
    assertEquals("Delayed", row.getDep().getStatus());
  }

  @Test
  void doesNotCallUnconfiguredArrivals() {
    var boards = new Boards();
    boards.departures = List.of(entry("train", "10:15", "Birmingham", ""));
    var result = service(boards, false).getBoard("MMO", 15);
    assertEquals(0, boards.arrivalsCalls);
    assertFalse(result.getArrivalsAvailable());
    assertEquals(1, result.getRowsCount());
    assertFalse(result.getRows(0).hasArr());
  }

  @Test
  void failedOptionalArrivalsDoNotDiscardDepartures() {
    var boards = new Boards();
    boards.failArrivals = true;
    boards.departures = List.of(entry("train", "10:15", "Birmingham", ""));
    var result = service(boards, true).getBoard("MMO", 15);
    assertFalse(result.getArrivalsAvailable());
    assertEquals(1, result.getRowsCount());
    assertTrue(result.getRows(0).hasDep());
  }

  @Test
  void failedDeparturesDoNotBecomeAnApparentlyEmptyBoard() {
    var boards = new Boards();
    boards.failDepartures = true;
    assertThrows(IllegalStateException.class, () -> service(boards, true).getBoard("MMO", 15));
  }

  @Test
  void missingServiceIdsDoNotMergeTrainsWithCoincidentTimesAndPlaces() {
    var boards = new Boards();
    boards.departures =
        List.of(entry("", "10:15", "Leicester", ""), entry("", "10:15", "Leicester", ""));
    boards.arrivals = List.of(entry("", "10:15", "", "Leicester"));
    var result = service(boards, true).getBoard("MMO", 15);
    assertEquals(3, result.getRowsCount());
    assertTrue(result.getRowsList().stream().noneMatch(r -> r.hasArr() && r.hasDep()));
  }

  @Test
  void unknownTimesSortLastAndAnEmptySuccessfulArrivalsBoardIsAvailable() {
    var boards = new Boards();
    boards.departures =
        List.of(entry("unknown", "", "Leicester", ""), entry("known", "10:15", "Birmingham", ""));
    var result = service(boards, true).getBoard("MMO", 15);
    assertEquals("known", result.getRows(0).getServiceId());
    assertEquals(Integer.MAX_VALUE, result.getRows(1).getSortSecs());
    assertTrue(result.getArrivalsAvailable());
  }
}
