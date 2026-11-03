package uk.org.tfm.server.service;

import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.feed.TrainBoardFeeds;

/**
 * Fetches the home station's train boards once at startup. The board APIs are on-demand, so without
 * this their health is unknown until a visitor opens a board: the data-sources page would read
 * "waiting for data" after every restart, and a bad or expired key would go unnoticed until then.
 * The fetch goes through {@link TrainBoardService}, so it also warms the board cache.
 *
 * <p>Runs on its own thread so a slow upstream never delays startup; failures are logged and
 * recorded as feed health, never thrown.
 */
@Singleton
public final class TrainBoardProbe {
  private static final Logger log = LoggerFactory.getLogger(TrainBoardProbe.class);
  static final String HOME_CRS = "MMO"; // Melton Mowbray

  private final TrainBoardService boards;
  private final TrainBoardFeeds feeds;
  private volatile Thread thread;

  @Inject
  public TrainBoardProbe(TrainBoardService boards, TrainBoardFeeds feeds) {
    this.boards = boards;
    this.feeds = feeds;
  }

  public void start() {
    thread = Thread.ofVirtual().name("train-board-probe").start(this::probe);
  }

  public void stop() {
    Thread t = thread;
    if (t != null) t.interrupt();
  }

  /**
   * Fetches each configured board once (row count 0 = the UI's default, so the cache is shared).
   */
  void probe() {
    if (feeds.departures().configured()) {
      check("departures", () -> boards.getDepartures(HOME_CRS, 0));
    }
    if (feeds.arrivals().configured()) {
      check("arrivals", () -> boards.getArrivals(HOME_CRS, 0));
    }
  }

  private static void check(String board, Runnable fetch) {
    try {
      fetch.run();
      log.info("Startup probe: {} {} board OK.", HOME_CRS, board);
    } catch (RuntimeException e) {
      log.warn("Startup probe: {} {} board failed: {}", HOME_CRS, board, e.getMessage());
    }
  }
}
