package uk.org.tfm.server.darwin.pushport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.rail.MovementSnapshot;
import uk.org.tfm.model.rail.PlatformUpdate;
import uk.org.tfm.model.rail.TrainScheduleDetails;
import uk.org.tfm.model.storage.StationPlatformStore;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.storage.TrainScheduleStore;

/** Maintains platform and movement state for the platform display; owns no transport lifecycle. */
@Singleton
public final class DarwinPlatformService {

  private static final Logger log = LoggerFactory.getLogger(DarwinPlatformService.class);

  /** How often the TTL sweep runs. */
  private static final long SWEEP_INTERVAL_SECONDS = 60;

  /**
   * Entries older than this are evicted on each sweep. 30 minutes covers a typical service call
   * plus generous slack for delays, while preventing the index from growing unboundedly overnight.
   */
  private static final Duration ENTRY_TTL = Duration.ofMinutes(30);

  /**
   * One train on a platform. Used by {@link #stationBoard(String)}.
   *
   * @param headcode 4-char TRUST signalling ID
   * @param origin display name of the origin station (best-effort from Darwin schedule)
   * @param destination display name of the destination station (best-effort from Darwin schedule)
   * @param confirmed {@code true} if Darwin has operationally confirmed the platform assignment
   */
  public record Occupant(
      String rid,
      String headcode,
      String origin,
      String destination,
      boolean confirmed,
      boolean atPlatformNow,
      boolean departed) {}

  /**
   * One row in the station platform board: a platform label and the trains currently on it. Empty
   * platforms (from {@code knownPlatforms}) appear with an empty {@code trains} list.
   *
   * @param platform platform label, e.g. {@code "1"}, {@code "3A"}
   * @param trains occupants (may be empty)
   */
  public record PlatformLine(String platform, List<Occupant> trains) {}

  private final PlatformIndex index;

  /**
   * Merged movement state (arr/dep actuals + estimates) per (dated RID, tpl). Owned by this service
   * rather than injected: it is consulted only here for the board's "at platform now" signal.
   * In-memory only and never persisted (the TS firehose must not hit the DB at sweep rate).
   * Populated on the consumer thread by {@link #onMovementSnapshot}, TTL-swept alongside {@link
   * #index}.
   */
  private final MovementIndex movementIndex = new MovementIndex();

  private final TrainScheduleStore scheduleDao;
  private final StationPlatformStore platformDao;
  private final PlatformScope scope;

  /** Permanent rid → headcode cache. Rids are stable for a given service call; the set is tiny. */
  private final ConcurrentHashMap<String, String> ridToHeadcode = new ConcurrentHashMap<>();

  /**
   * Per-TIPLOC set of platform labels ever observed (non-suppressed, non-blank). Never evicted in
   * memory — retaining historic labels ensures the board always shows the station's full physical
   * layout even when platforms are momentarily empty. Seeded from {@code station_platforms} on
   * {@link #start()} (so a cold board is fully populated) and flushed back via the sweep; see
   * {@link #seedKnownPlatforms} and {@link #flushDirtyPlatforms}. Values are created on first use
   * via {@link ConcurrentHashMap#newKeySet()}.
   */
  private final ConcurrentHashMap<String, Set<String>> knownPlatforms = new ConcurrentHashMap<>();

  /**
   * TIPLOCs whose {@link #knownPlatforms} gained a new label since the last DB flush. Marked on the
   * Kafka consumer thread in {@link #onUpdate}, drained on the sweep thread in {@link
   * #flushDirtyPlatforms}. Keeps the per-message path off JDBC; in steady state (inventory warm)
   * this stays empty and the sweep flush is a no-op.
   */
  private final Set<String> dirtyTiplocs = ConcurrentHashMap.newKeySet();

  /** Count of updates dropped because rid was not found in darwin_schedules (debug telemetry). */
  private final LongAdder droppedNoSchedule = new LongAdder();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "darwin-platform-sweep");
            t.setDaemon(true);
            return t;
          });

  private volatile boolean running;

  @Inject
  public DarwinPlatformService(
      PlatformIndex index,
      TrainScheduleStore scheduleDao,
      StationPlatformStore platformDao,
      PlatformScope scope) {
    this.index = index;
    this.scheduleDao = scheduleDao;
    this.platformDao = platformDao;
    this.scope = scope;
  }

  /**
   * Seeds historic labels even when Kafka is disabled; starts maintenance only with a live feed.
   */
  public synchronized void start(boolean feedEnabled) {
    if (running) return;
    seedKnownPlatforms();
    if (!feedEnabled) return;
    running = true;
    scheduler.scheduleAtFixedRate(
        this::sweep, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
  }

  public synchronized void stop() {
    running = false;
    scheduler.shutdownNow();
    flushDirtyPlatforms();
  }

  /** Make a new headcode available to platform/movement correlation before the storage flush. */
  public void onScheduleDetails(TrainScheduleDetails record) {
    String headcode = record.header().trainId();
    if (headcode != null && !headcode.isBlank()) {
      ridToHeadcode.put(record.header().rid(), headcode);
    }
  }

  /**
   * Returns the platform board for the given TIPLOC: one {@link PlatformLine} per platform, sorted
   * naturally (1, 2, 3A, 10). Platforms in {@code knownPlatforms} that have no current occupant are
   * included with an empty trains list. Occupants have their origin and destination enriched from
   * {@code darwin_schedules} via {@link TrainScheduleStore#byRid}, degrading to the raw TIPLOC when
   * the schedule is not loaded.
   *
   * @param tpl TIPLOC of the station
   * @return board lines, empty when the feed is disabled or no platforms are known
   */
  public List<PlatformLine> stationBoard(String tpl) {
    // Collect all platform labels: those we already know about (never evicted) plus whatever is
    // live in the index right now (covers the first moments before knownPlatforms is warm).
    List<PlatformIndex.Stored> liveEntries = index.forTpl(tpl);

    Set<String> allPlatforms = new TreeSet<>(PLATFORM_COMPARATOR);
    Set<String> known = knownPlatforms.get(tpl);
    if (known != null) {
      allPlatforms.addAll(known);
    }
    for (PlatformIndex.Stored s : liveEntries) {
      String p = s.update().platform();
      if (p != null && !p.isBlank()) {
        allPlatforms.add(p);
      }
    }

    // Build a per-platform occupant list from the live index snapshot.
    List<PlatformLine> board = new ArrayList<>(allPlatforms.size());
    for (String platform : allPlatforms) {
      List<Occupant> occupants = new ArrayList<>();
      for (PlatformIndex.Stored s : liveEntries) {
        if (!platform.equals(s.update().platform())) {
          continue;
        }
        String origin = "";
        String destination = "";
        var schedule = scheduleDao.byRid(s.update().rid());
        if (schedule.isPresent()) {
          origin = scope.nameForTpl(schedule.get().originTpl()).orElse(schedule.get().originTpl());
          destination = scope.nameForTpl(schedule.get().destTpl()).orElse(schedule.get().destTpl());
        }
        var movement = movementIndex.lookup(s.update().rid(), s.update().tpl());
        occupants.add(
            new Occupant(
                s.update().rid(),
                s.headcode(),
                origin,
                destination,
                s.update().confirmed(),
                movement.map(MovementSnapshot::atPlatformNow).orElse(false),
                movement.map(m -> !m.depAt().isBlank() || !m.passAt().isBlank()).orElse(false)));
      }
      board.add(new PlatformLine(platform, occupants));
    }
    return board;
  }

  // ---- internal ----

  // Package-private for unit tests (the feed sink is this::onUpdate at runtime).
  void onUpdate(PlatformUpdate update) {

    // Inventory first, BEFORE the rid→headcode correlation. A platform label needs no schedule —
    // only the TIPLOC — so the station's known-platforms layout (and its persisted backing table)
    // must accumulate even when the rid is not in darwin_schedules. Only non-suppressed, non-blank
    // labels are recorded; a genuinely new label marks the TIPLOC dirty for the next sweep flush.
    if (!update.suppressed() && update.platform() != null && !update.platform().isBlank()) {
      if (knownPlatforms
          .computeIfAbsent(update.tpl(), k -> ConcurrentHashMap.newKeySet())
          .add(update.platform())) {
        dirtyTiplocs.add(update.tpl());
      }
    }

    // Occupant correlation needs rid→headcode (and therefore a loaded timetable). When the rid is
    // unknown we still kept the label above; we just can't say which train is on the platform.
    String headcode = ridToHeadcode.computeIfAbsent(update.rid(), this::resolveHeadcode);
    if (headcode == null) {
      droppedNoSchedule.increment();
      log.debug(
          "Darwin platform: no schedule for rid '{}' tpl '{}' — label kept, occupant dropped.",
          update.rid(),
          update.tpl());
      return;
    }
    index.put(headcode, update);
  }

  /**
   * Handles one Darwin movement snapshot: resolves its rid to a headcode (shared cache) and merges
   * it into {@link #movementIndex}. An uncorrelated rid is dropped — without a headcode the
   * movement cannot be tied to a board occupant or a tracked train. Runs on the Kafka consumer
   * thread.
   */
  public void onMovementSnapshot(MovementSnapshot movement) {
    String headcode = ridToHeadcode.computeIfAbsent(movement.rid(), this::resolveHeadcode);
    if (headcode == null) {
      droppedNoSchedule.increment();
      return;
    }
    movementIndex.merge(movement.rid(), movement);
  }

  /** A missing schedule is retried on later updates; null is never cached. */
  private String resolveHeadcode(String rid) {
    Optional<String> found = scheduleDao.headcodeForRid(rid);
    if (found.isPresent()) {
      return found.get();
    }
    // Return null — computeIfAbsent will NOT cache this (ConcurrentHashMap prohibits null values),
    // so the DAO will be consulted again on the next update for this rid. That is intentional:
    // if the timetable is loaded incrementally we want to pick up the schedule once it arrives.
    return null;
  }

  private void sweep() {
    try {
      Instant now = Instant.now();
      int removed = index.sweep(now, ENTRY_TTL);
      if (removed > 0) {
        log.debug(
            "Darwin platform sweep: evicted {} stale entries ({} remaining).",
            removed,
            index.size());
      }
      int movementsRemoved = movementIndex.sweep(now, ENTRY_TTL);
      if (movementsRemoved > 0) {
        log.debug(
            "Darwin movement sweep: evicted {} stale entries ({} remaining).",
            movementsRemoved,
            movementIndex.size());
      }
      long dropped = droppedNoSchedule.sumThenReset();
      if (dropped > 0) {
        log.debug(
            "Darwin platform: {} updates dropped this interval (rid not in timetable).", dropped);
      }
      flushDirtyPlatforms();
    } catch (RuntimeException e) {
      log.warn("Darwin platform sweep failed", e); // never let the scheduled task die
    }
  }

  /**
   * Seeds {@link #knownPlatforms} from the persisted inventory so the station board shows the full
   * platform layout immediately — across restarts and even when the live feed is disabled. Merges
   * into (never replaces) the in-memory sets, so it is safe to run before the feed starts. A DB
   * failure is logged and swallowed: the board then warms up from the live feed alone.
   */
  private void seedKnownPlatforms() {
    try {
      Map<String, Set<String>> loaded = platformDao.loadAll();
      int labels = 0;
      for (Map.Entry<String, Set<String>> e : loaded.entrySet()) {
        knownPlatforms
            .computeIfAbsent(e.getKey(), k -> ConcurrentHashMap.newKeySet())
            .addAll(e.getValue());
        labels += e.getValue().size();
      }
      log.info(
          "Darwin platform service: seeded {} platform labels across {} stations from DB.",
          labels,
          loaded.size());
    } catch (StorageException e) {
      log.warn(
          "Darwin platform service: failed to seed platforms from DB "
              + "— board will warm up from the live feed only.",
          e);
    }
  }

  /**
   * Batch-upserts the platforms of every TIPLOC marked dirty since the last flush, then clears the
   * marks. Runs on the sweep thread (off the consumer poll loop). On failure the dirty TIPLOCs are
   * re-enqueued so the next sweep retries; a concurrent {@link #onUpdate} only ever re-adds a mark,
   * so at worst we do a harmless duplicate upsert.
   */
  // Package-private for unit tests.
  void flushDirtyPlatforms() {
    if (dirtyTiplocs.isEmpty()) {
      return;
    }
    Set<String> toFlush = new HashSet<>(dirtyTiplocs);
    dirtyTiplocs.removeAll(toFlush);
    Map<String, Set<String>> snapshot = new HashMap<>(toFlush.size());
    for (String tpl : toFlush) {
      Set<String> labels = knownPlatforms.get(tpl);
      if (labels != null && !labels.isEmpty()) {
        snapshot.put(tpl, new HashSet<>(labels)); // copy: don't hand a live set to the DAO
      }
    }
    try {
      platformDao.upsertBatch(snapshot, Instant.now());
      log.debug("Darwin platform flush: upserted platforms for {} stations.", snapshot.size());
    } catch (StorageException e) {
      dirtyTiplocs.addAll(toFlush); // retry next sweep
      log.warn(
          "Darwin platform flush failed — {} TIPLOCs re-queued for next sweep.", toFlush.size(), e);
    }
  }

  // ---- platform natural sort ----

  /**
   * Sorts platform labels naturally: numeric prefix ascending, then lexicographic suffix. Examples:
   * "1" < "2" < "3A" < "10" < "10A" < "Depot". Labels with no leading digits sort after all numeric
   * ones.
   */
  static final Comparator<String> PLATFORM_COMPARATOR =
      (a, b) -> {
        int[] aLen = {0};
        int[] bLen = {0};
        long aNum = leadingNumber(a, aLen);
        long bNum = leadingNumber(b, bLen);
        boolean aHasNum = aLen[0] > 0;
        boolean bHasNum = bLen[0] > 0;
        if (aHasNum && bHasNum) {
          int cmp = Long.compare(aNum, bNum);
          if (cmp != 0) {
            return cmp;
          }
          // Same numeric prefix — compare the trailing suffix lexicographically.
          return a.substring(aLen[0]).compareTo(b.substring(bLen[0]));
        }
        // One or both have no numeric prefix: numeric-prefixed entries sort first.
        if (aHasNum) {
          return -1;
        }
        if (bHasNum) {
          return 1;
        }
        return a.compareTo(b);
      };

  /**
   * Parses the leading decimal digits of {@code s} into a long; sets {@code lenOut[0]} to the
   * number of digit characters consumed. If {@code s} has no leading digit, {@code lenOut[0] = 0}
   * and the return value is 0.
   */
  private static long leadingNumber(String s, int[] lenOut) {
    int i = 0;
    while (i < s.length() && Character.isDigit(s.charAt(i))) {
      i++;
    }
    lenOut[0] = i;
    if (i == 0) {
      return 0;
    }
    return Long.parseLong(s, 0, i, 10);
  }
}
