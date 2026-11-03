package uk.org.tfm.server.darwin.pushport;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.rail.MovementSnapshot;
import uk.org.tfm.model.rail.PlatformUpdate;
import uk.org.tfm.model.rail.TrainEventFeed;
import uk.org.tfm.model.rail.TrainEventSink;
import uk.org.tfm.model.rail.TrainScheduleDetails;
import uk.org.tfm.server.config.Config;

/** Owns the train feed lifecycle and dispatches schedules independently of platform maintenance. */
@Singleton
public final class DarwinFeedCoordinator {
  private static final Logger log = LoggerFactory.getLogger(DarwinFeedCoordinator.class);
  private final TrainEventFeed feed;
  private final DarwinScheduleFeedService schedules;
  private final DarwinPlatformService platforms;
  private final boolean enabled;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "darwin-schedule-flush");
            t.setDaemon(true);
            return t;
          });
  private volatile boolean running;
  private volatile Instant lastMessageAt;

  @Inject
  public DarwinFeedCoordinator(
      Config config,
      TrainEventFeed feed,
      DarwinScheduleFeedService schedules,
      DarwinPlatformService platforms) {
    this(config.platformFeedEnabled(), feed, schedules, platforms);
  }

  DarwinFeedCoordinator(
      boolean enabled,
      TrainEventFeed feed,
      DarwinScheduleFeedService schedules,
      DarwinPlatformService platforms) {
    this.enabled = enabled;
    this.feed = feed;
    this.schedules = schedules;
    this.platforms = platforms;
  }

  public synchronized void start() {
    if (running) return;
    platforms.start(enabled);
    if (!enabled) return;
    running = true;
    feed.start(
        new TrainEventSink() {
          @Override
          public void onPlatformUpdate(PlatformUpdate update) {
            lastMessageAt = Instant.now();
            platforms.onUpdate(update);
          }

          @Override
          public void onScheduleDetails(TrainScheduleDetails record) {
            lastMessageAt = Instant.now();
            schedules.onRecord(record);
            platforms.onScheduleDetails(record);
          }

          @Override
          public void onMovement(MovementSnapshot movement) {
            lastMessageAt = Instant.now();
            platforms.onMovementSnapshot(movement);
          }
        });
    scheduler.scheduleAtFixedRate(this::flushSchedules, 60, 60, TimeUnit.SECONDS);
  }

  public synchronized void stop() {
    running = false;
    feed.stop();
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow();
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
    flushSchedules();
    platforms.stop();
  }

  private void flushSchedules() {
    try {
      schedules.flush();
    } catch (RuntimeException e) {
      log.warn("Darwin schedule flush failed", e);
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isRunning() {
    return running;
  }

  public Optional<Instant> lastMessageAt() {
    return Optional.ofNullable(lastMessageAt);
  }
}
