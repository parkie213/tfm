package uk.org.tfm.server.darwin.pushport;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.rail.ScheduleRecord;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.storage.TrainScheduleStore;

/**
 * Persists live Darwin push-port {@code schedule} (SC) records into {@code darwin_schedules} so the
 * platform board's {@code rid → headcode → origin/destination} resolution stays fresh from the feed
 * and survives restarts.
 *
 * <p>Mirrors the platform-inventory persistence pattern: {@link #onRecord} runs on the Kafka
 * consumer thread and only buffers the latest record per rid; {@link #flush} runs on the feed
 * coordinator's 60 s sweep thread and batch-upserts off the poll loop. Idempotent — re-upserting a
 * rid is harmless; a failed flush re-queues for the next sweep. Old schedules are evicted on a
 * throttle to bound growth.
 */
@Singleton
public final class DarwinScheduleFeedService {

  private static final Logger log = LoggerFactory.getLogger(DarwinScheduleFeedService.class);
  private static final ZoneId LONDON = ZoneId.of("Europe/London");

  /** Run eviction roughly every 30 min at the 60 s sweep cadence (cheap when nothing to delete). */
  private static final int EVICT_EVERY_N_FLUSHES = 30;

  private static final int RETAIN_DAYS = 2;

  private final TrainScheduleStore dao;
  // rid -> latest record awaiting persistence. Written on the consumer thread, drained on the
  // sweep.
  private final ConcurrentHashMap<String, ScheduleRecord> pending = new ConcurrentHashMap<>();
  private int flushCount;

  @Inject
  public DarwinScheduleFeedService(TrainScheduleStore dao) {
    this.dao = dao;
  }

  /** Consumer thread: buffer the latest SC for this rid; the sweep persists it. */
  public void onRecord(ScheduleRecord record) {
    pending.put(record.header().rid(), record);
  }

  /** Sweep thread: batch-upsert buffered schedules, then (throttled) evict stale ones. */
  public void flush() {
    if (!pending.isEmpty()) {
      // Snapshot keys first, then remove exactly those — a concurrent onRecord only re-adds (safe).
      Set<String> keys = new HashSet<>(pending.keySet());
      List<ScheduleRecord> batch = new ArrayList<>(keys.size());
      for (String rid : keys) {
        ScheduleRecord r = pending.get(rid);
        if (r != null) {
          batch.add(r);
        }
      }
      pending.keySet().removeAll(keys);
      try {
        dao.upsertLiveBatch(batch);
        log.debug("Darwin SC flush: upserted {} live schedules.", batch.size());
      } catch (StorageException e) {
        for (ScheduleRecord r : batch) {
          pending.putIfAbsent(r.header().rid(), r); // re-queue; don't clobber a fresher arrival
        }
        log.warn(
            "Darwin SC flush failed — {} schedules re-queued for next sweep.", batch.size(), e);
      }
    }
    if (++flushCount % EVICT_EVERY_N_FLUSHES == 0) {
      long removed = dao.evictOldSchedules(LocalDate.now(LONDON).minusDays(RETAIN_DAYS));
      if (removed > 0) {
        log.info(
            "Darwin SC eviction: removed {} schedules older than {} days.", removed, RETAIN_DAYS);
      }
    }
  }

  /** Schedules buffered awaiting the next flush (debug/observability). */
  public int pendingCount() {
    return pending.size();
  }
}
