package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.board.Board;
import uk.org.tfm.model.board.BoardProvider;
import uk.org.tfm.model.feed.FeedHealth;
import uk.org.tfm.model.feed.TrainBoardFeeds;

class TrainBoardProbeTest {

  /** Records each board fetch; fails the arrivals call when asked to. */
  private static final class Boards implements BoardProvider {
    final List<String> calls = new ArrayList<>();
    boolean failArrivals;

    public Board getDepartureBoard(String station, int rows) {
      calls.add("dep:" + station + ":" + rows);
      return new Board("Melton Mowbray", "", "", List.of());
    }

    public Board getArrivalBoard(String station, int rows) {
      calls.add("arr:" + station + ":" + rows);
      if (failArrivals) throw new RuntimeException("HTTP 401: invalid key");
      return new Board("Melton Mowbray", "", "", List.of());
    }
  }

  private static FeedHealth configured(boolean configured) {
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

  private static TrainBoardProbe probe(Boards boards, boolean dep, boolean arr) {
    return new TrainBoardProbe(
        new TrainBoardService(boards), new TrainBoardFeeds(configured(dep), configured(arr)));
  }

  @Test
  void fetchesBothConfiguredBoardsWithTheUiDefaultRowCount() {
    var boards = new Boards();
    probe(boards, true, true).probe();
    assertEquals(List.of("dep:MMO:10", "arr:MMO:10"), boards.calls);
  }

  @Test
  void skipsBoardsThatAreNotConfigured() {
    var boards = new Boards();
    probe(boards, true, false).probe();
    assertEquals(List.of("dep:MMO:10"), boards.calls);

    var none = new Boards();
    probe(none, false, false).probe();
    assertTrue(none.calls.isEmpty());
  }

  @Test
  void aFailingBoardIsLoggedNotThrownAndDoesNotStopTheOthers() {
    var boards = new Boards();
    boards.failArrivals = true;
    var probe = probe(boards, true, true);
    assertDoesNotThrow(probe::probe);
    assertEquals(List.of("dep:MMO:10", "arr:MMO:10"), boards.calls);
  }

  @Test
  void probeWarmsTheBoardCacheTheUiReads() {
    var boards = new Boards();
    var service = new TrainBoardService(boards);
    new TrainBoardProbe(service, new TrainBoardFeeds(configured(true), configured(false))).probe();
    service.getDepartures("MMO", 0); // the UI's request: served from cache, no second fetch
    assertEquals(List.of("dep:MMO:10"), boards.calls);
  }
}
