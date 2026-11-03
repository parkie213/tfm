package uk.org.tfm.server.bods;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.vehicle.VehiclePosition;
import uk.org.tfm.model.vehicle.VehiclePositionProvider;
import uk.org.tfm.server.config.Config;

/**
 * Polls the upstream feed, processes successful fetches, and publishes snapshots to browser
 * streams.
 */
@Singleton
public final class BusVehiclePoller {
  private static final Logger log = LoggerFactory.getLogger(BusVehiclePoller.class);
  private static final long FIRST_BACKOFF_SECONDS = 20;
  private static final long MAX_BACKOFF_SECONDS = 160;
  private static final long RATE_LIMIT_BACKOFF_SECONDS = 60;
  private final VehiclePositionProvider client;
  private final BusVehicleProcessor processor;
  private final BusVehicleService snapshots;
  private final boolean enabled;
  private final long baseDelaySeconds;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "bus-poller");
            t.setDaemon(true);
            return t;
          });

  private volatile boolean running;
  private int consecutiveFailures;

  @Inject
  public BusVehiclePoller(
      VehiclePositionProvider client,
      BusVehicleProcessor processor,
      BusVehicleService snapshots,
      Config config) {
    this.client = client;
    this.processor = processor;
    this.snapshots = snapshots;
    enabled = config.busVehiclesEnabled();
    baseDelaySeconds = Math.max(5, config.bodsPollSeconds());
  }

  public synchronized void start() {
    if (!enabled) {
      log.warn("BODS_API_KEY not set — live bus positions are disabled.");
      return;
    }
    if (running) {
      return;
    }
    running = true;
    scheduler.schedule(this::poll, 0, TimeUnit.SECONDS); // first poll immediately
    log.info("Live bus poller started (~every {}s).", baseDelaySeconds);
  }

  public synchronized void stop() {
    running = false;
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        processor.close();
      } finally {
        snapshots.close();
      }
    }
  }

  private void poll() {
    long nextDelay = pollOnce();
    if (running) {
      try {
        scheduler.schedule(this::poll, nextDelay, TimeUnit.SECONDS);
      } catch (RejectedExecutionException ignored) {
      }
    }
  }

  /** One cycle, exposed within the package for deterministic retry tests without sleeping. */
  long pollOnce() {
    long nextDelay = baseDelaySeconds;
    try {
      long start = System.nanoTime();
      List<VehiclePosition> vehicles = client.fetch();
      long fetched = System.nanoTime();
      var next = processor.process(vehicles);
      snapshots.publish(next);
      consecutiveFailures = 0;
      long matched = next.vehicles().stream().filter(v -> !v.tripId().isBlank()).count();
      log.info(
          "Bus snapshot: {} vehicles, {} matched (fetch {}ms, process {}ms).",
          vehicles.size(),
          matched,
          ms(start, fetched),
          ms(fetched, System.nanoTime()));
    } catch (FeedException e) {
      consecutiveFailures++;
      if (e.kind() == FeedException.Kind.RATE_LIMITED) {
        nextDelay =
            Math.max(
                RATE_LIMIT_BACKOFF_SECONDS,
                e.retryAfter().map(Duration::toSeconds).orElse(RATE_LIMIT_BACKOFF_SECONDS));
        log.warn("BODS rate-limited; backing off {}s.", nextDelay);
      } else {
        nextDelay = backoff(consecutiveFailures);
        log.warn("Bus feed fetch failed ({}); retrying in {}s.", e.getMessage(), nextDelay);
      }
    } catch (Throwable failure) {
      consecutiveFailures++;
      nextDelay = backoff(consecutiveFailures);
      log.error("Unexpected error in bus poller; retrying in {}s.", nextDelay, failure);
    }
    return nextDelay;
  }

  private static long ms(long fromNanos, long toNanos) {
    return (toNanos - fromNanos) / 1_000_000;
  }

  private static long backoff(int failures) {
    long delay = (long) (FIRST_BACKOFF_SECONDS * Math.pow(2, Math.max(0, failures - 1)));
    return Math.min(delay, MAX_BACKOFF_SECONDS);
  }
}
