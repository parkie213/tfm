package uk.org.tfm.server.gtfs;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.storage.LoadState;
import uk.org.tfm.model.storage.LoadStateStore;
import uk.org.tfm.model.storage.TimetableStore;
import uk.org.tfm.model.timetable.TimetableProvider;
import uk.org.tfm.server.config.Config;
import uk.org.tfm.server.service.GtfsFeedInfo;

/**
 * Refreshes changed provider timetables on startup and daily, then asks for map geometry refresh.
 */
@Singleton
public final class TimetableRefreshService implements GtfsFeedInfo {

  private static final Logger log = LoggerFactory.getLogger(TimetableRefreshService.class);
  private static final long RELOAD_INTERVAL_HOURS = 24;

  private final LoadStateStore loadStates;
  private final BusGeometryBuilder geometry;
  private final List<String> regions; // e.g. ["east_midlands", "london"]
  private final boolean enabled;
  private final TimetableProvider timetables;
  private final TimetableStore timetableStore;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "timetable-refresh");
            t.setDaemon(true);
            return t;
          });

  private volatile boolean running;

  @Inject
  public TimetableRefreshService(
      LoadStateStore loadStates,
      BusGeometryBuilder geometry,
      Config config,
      TimetableProvider timetables,
      TimetableStore timetableStore) {
    this.loadStates = loadStates;
    this.geometry = geometry;
    this.enabled = config.busTimetableEnabled();
    this.timetables = timetables;
    this.timetableStore = timetableStore;
    this.regions = List.copyOf(timetables.regions());
  }

  // ---- DataSourceRegistry accessors (read-only, side-effect-free) ----

  /** Whether GTFS timetable loading is enabled (BUS_TIMETABLE_ENABLED=true). */
  public boolean isEnabled() {
    return enabled;
  }

  /** The BODS GTFS regions this loader is configured to fetch, e.g. {@code ["east_midlands"]}. */
  public List<String> getRegions() {
    return List.copyOf(regions);
  }

  public synchronized void start() {
    if (!enabled) {
      log.warn("BUS_TIMETABLE_ENABLED=false — Timetable refresh is disabled.");
      return;
    }
    if (running) {
      return;
    }
    running = true;
    scheduler.schedule(this::load, 0, TimeUnit.SECONDS);
    log.info(
        "Timetable refresh started; regions={}; will reload every {}h.",
        regions,
        RELOAD_INTERVAL_HOURS);
  }

  public synchronized void stop() {
    running = false;
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---- Load cycle ----

  /** Decode and import only changed feeds; geometry has its own independent freshness gate. */
  private void load() {
    long cycleStart = System.currentTimeMillis();
    try {
      log.info("GTFS bulk load starting: regions={}", regions);

      // Resolve each region's zip (downloads only if the 24h cache is stale), then fingerprint the
      // bytes — the feed version the gates compare against.
      var source = timetables.fetch();
      String feedVersion = source.version();
      LoadState prev = loadStates.read();

      // ---- Core gate: the gtfs_* tables derive only from the feed. ----
      if (feedVersion.equals(prev.feedVersion()) && timetableStore.hasTimetable()) {
        log.info(
            "GTFS feed unchanged (version {}) — core tables already loaded, skipping COPY.",
            shortHash(feedVersion));
      } else {
        // Stream the changed feed straight into storage; per-table row counts are logged as each
        // table is copied. A failure part-way leaves the previous timetable in place.
        timetableStore.bulkLoad(source.read());
        loadStates.setFeed(feedVersion, Instant.now());
        long cycleMs = System.currentTimeMillis() - cycleStart;
        log.info("GTFS bulk load complete in {}ms (total).", cycleMs);
      }

      geometry.refresh(feedVersion, prev);
    } catch (Throwable t) {
      log.error("Timetable or geometry refresh failed; completed imports remain available.", t);
    } finally {
      if (running) {
        try {
          scheduler.schedule(this::load, RELOAD_INTERVAL_HOURS, TimeUnit.HOURS);
        } catch (RejectedExecutionException ignored) {
          // executor shutting down
        }
      }
    }
  }

  private static String shortHash(String h) {
    return h == null ? "none" : h.substring(0, Math.min(8, h.length()));
  }
}
