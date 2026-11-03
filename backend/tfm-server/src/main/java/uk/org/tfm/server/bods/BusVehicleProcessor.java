package uk.org.tfm.server.bods;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.timetable.JourneyKey;
import uk.org.tfm.model.vehicle.VehiclePosition;
import uk.org.tfm.proto.v1.LatLng;
import uk.org.tfm.server.service.BusTimetableService;

/**
 * Single-poller vehicle processing: trip matching, recent tracks, and asynchronous road snapping.
 */
@Singleton
public final class BusVehicleProcessor implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(BusVehicleProcessor.class);
  private final BusTimetableService timetables;

  /** Keep the latest two fixes for client animation and expire inactive tracks. */
  private static final int MAX_FIXES = 2;

  private static final long RETENTION_SECONDS = 6 * 60;

  /**
   * A fix further than this from the route isn't a confident snap (GPS noise vs a genuine
   * diversion): beyond it the marker stays at raw GPS. A touch above the client's 50 m
   * enter-threshold, since this server snap is the only correction non-followed buses get.
   */
  private static final double SNAP_GATE_M = 60;

  /**
   * Trip-resolution cache freshness. A journey→trip mapping is stable for the journey's life, so a
   * positive resolution is held long (re-checked occasionally in case a timetable reload changed
   * ids); a negative ("no unique trip") is re-checked sooner so a journey unmatched only because
   * the GTFS timetable wasn't loaded yet heals shortly after the load completes.
   */
  private static final long RESOLUTION_POS_TTL_SECONDS = 30 * 60;

  private static final long RESOLUTION_NEG_TTL_SECONDS = 90;

  /** A parsed vehicle plus its resolved GTFS trip_id ("" if no unique match). */
  record EnrichedVehicle(VehiclePosition pos, String tripId) {}

  /** A cached journey→trip resolution. {@code tripId} is "" for a negative (no unique match). */
  private record Resolution(String tripId, Instant resolvedAt) {}

  private record Estimates(
      Map<String, RouteSnapper.Snap> snappedByVehicle,
      Map<String, VehiclePosition> prevByVehicle) {}

  record Snapshot(
      List<EnrichedVehicle> vehicles,
      Map<String, RouteSnapper.Snap> snappedByVehicle,
      Map<String, VehiclePosition> prevByVehicle,
      Instant fetchedAt) {
    Snapshot {
      vehicles = List.copyOf(vehicles);
      snappedByVehicle = Map.copyOf(snappedByVehicle);
      prevByVehicle = Map.copyOf(prevByVehicle);
    }
  }

  /**
   * One physical vehicle's recent trajectory (poller-thread-only state). Keyed by VehicleRef — the
   * stable physical identity — with the current journey's trip_id stored inside; the buffer is
   * cleared when that trip_id changes so a new journey never inherits the previous run's history.
   */
  private static final class VehicleTrack {
    String tripId = "";
    final Deque<VehiclePosition> fixes = new ArrayDeque<>(); // oldest → newest, ≤ MAX_FIXES
    Instant lastFixAt = Instant.EPOCH;
    double lastSnappedS =
        -1; // last road-snapped chainage (metres); -1 = none yet → window disabled

    /** Adds a fix, deduping repeats and dropping out-of-order/old ones; bounds the buffer. */
    void ingest(VehiclePosition p) {
      Instant t = p.recordedAt();
      if (t == null || t.equals(Instant.EPOCH)) {
        return;
      }
      VehiclePosition newest = fixes.peekLast();
      if (newest != null && !t.isAfter(newest.recordedAt())) {
        return; // duplicate or out-of-order — the latest fix already supersedes it
      }
      fixes.addLast(p);
      lastFixAt = t;
      while (fixes.size() > MAX_FIXES) {
        fixes.removeFirst();
      }
      Instant cutoff = t.minusSeconds(RETENTION_SECONDS);
      while (fixes.size() > 1 && fixes.peekFirst().recordedAt().isBefore(cutoff)) {
        fixes.removeFirst();
      }
    }
  }

  // Road geometry for snapping live positions onto the carriageway. Populated off the poller thread
  // (a shape fetch may hit OSRM for shapeless operators — must not delay the snapshot), then read
  // on
  // the poller thread, so both maps are concurrent. `shapeAttempted` records trips we've already
  // kicked a fetch for (success or no-geometry) so we don't re-fetch every poll; both are bounded
  // to
  // currently-active trips each poll.
  private final Map<String, RouteSnapper.Route> routeShapes = new ConcurrentHashMap<>();
  private final Set<String> shapeAttempted = ConcurrentHashMap.newKeySet();
  private final ExecutorService shapeFetcher =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "bus-shape-fetcher");
            t.setDaemon(true);
            return t;
          });

  // ---- Poller-thread-only retained state (never read off-thread) ----
  private final Map<JourneyKey, Resolution> tripCache = new HashMap<>(); // journey → trip_id
  private final Map<String, VehicleTrack> tracks = new HashMap<>(); // VehicleRef → trajectory
  private final Map<String, Instant> loggedUnmatched =
      new HashMap<>(); // vehicleKey → unmatched-since

  @Inject
  public BusVehicleProcessor(BusTimetableService timetables) {
    this.timetables = timetables;
  }

  Snapshot process(List<VehiclePosition> vehicles) {
    List<EnrichedVehicle> enriched = enrich(vehicles);
    Estimates estimates = updateTracks(enriched);
    return new Snapshot(
        enriched, estimates.snappedByVehicle(), estimates.prevByVehicle(), Instant.now());
  }

  @Override
  public void close() {
    shapeFetcher.shutdownNow();
    try {
      shapeFetcher.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Resolves each vehicle's GTFS trip_id, event-driven: a journey's identity → trip mapping is
   * stable for its whole life, so we resolve it once (when the journey first appears) into a cache
   * that survives polls, and only batch-resolve the misses each poll — one query instead of one per
   * journey. Runs on the poller thread, so the per-RPC read path stays a lock-free snapshot read. A
   * DB hiccup leaves a journey unresolved (retried next poll) rather than poisoning the cache.
   */
  private List<EnrichedVehicle> enrich(List<VehiclePosition> vehicles) {
    Instant now = Instant.now();

    // 1. Which journeys are present this poll, and which are cache misses (unseen or stale)?
    Set<JourneyKey> active = new HashSet<>();
    List<JourneyKey> misses = new ArrayList<>();
    for (VehiclePosition v : vehicles) {
      JourneyKey key = journeyKey(v);
      if (key == null) {
        continue;
      }
      if (active.add(key)) {
        Resolution r = tripCache.get(key);
        if (r == null || isExpired(r, now)) {
          misses.add(key);
        }
      }
    }

    // 2. Resolve all misses in one round trip. A journey the batch doesn't return is a negative
    //    ("" — no unique trip); a query *failure* is left unresolved so it retries (never cached).
    if (!misses.isEmpty()) {
      try {
        Map<JourneyKey, String> found = timetables.matchTripIds(misses);
        for (JourneyKey key : misses) {
          tripCache.put(key, new Resolution(found.getOrDefault(key, ""), now));
        }
      } catch (StorageException | RuntimeException e) {
        log.debug("Batched trip match failed for {} journeys: {}", misses.size(), e.toString());
      }
    }

    // 3. Build the enriched list from the cache, and log any vehicle we still can't tie to a trip
    // so the
    //    cause is visible in the server logs (deduped per vehicle — one line when it goes
    // unmatched, not
    //    every poll). Two reasons distinguished: the feed omitted the journey identity (null key —
    // the
    //    notorious BODS/AMID gap), or it carried one but no unique timetable trip matched (a real
    // match
    //    miss worth investigating). A bus that recovers then breaks again logs afresh.
    List<EnrichedVehicle> out = new ArrayList<>(vehicles.size());
    Set<String> stillUnmatched = new HashSet<>();
    for (VehiclePosition v : vehicles) {
      JourneyKey key = journeyKey(v);
      Resolution r = key == null ? null : tripCache.get(key);
      String tripId = r == null ? "" : r.tripId();
      out.add(new EnrichedVehicle(v, tripId));
      if (tripId.isBlank()) {
        String vk = vehicleKey(v);
        stillUnmatched.add(vk);
        if (loggedUnmatched.putIfAbsent(vk, now) == null) {
          log.info(
              "Live bus unmatched ({}): line={} op={} veh={} origin={} dep={} date={} dest=\"{}\"",
              key == null
                  ? "feed omitted journey identity"
                  : "no unique timetable trip for journey",
              v.line(),
              v.operator(),
              v.id(),
              v.originRef(),
              depHmm(v),
              v.serviceDate(),
              v.destination());
        }
      }
    }

    // 4. Bound the per-poll caches to what's present now: departed journeys fall out of the trip
    // cache,
    //    and recovered/departed buses fall out of the unmatched-log set (so a later break re-logs).
    tripCache.keySet().retainAll(active);
    loggedUnmatched.keySet().retainAll(stillUnmatched);
    return out;
  }

  /** The vehicle's scheduled-journey identity, or null if the feed didn't carry enough to match. */
  private static JourneyKey journeyKey(VehiclePosition v) {
    if (v.line().isBlank()
        || v.originRef().isBlank()
        || v.originDepartureSecsLocal() < 0
        || v.serviceDate().isBlank()) {
      return null;
    }
    try {
      return new JourneyKey(
          v.line(),
          v.originRef(),
          v.originDepartureSecsLocal(),
          LocalDate.parse(v.serviceDate()),
          v.destination() == null ? "" : v.destination());
    } catch (RuntimeException e) {
      return null; // unparseable service date — leave unmatched
    }
  }

  /** The journey's origin departure as local HH:mm, or "n/a" if the feed didn't carry it. */
  private static String depHmm(VehiclePosition v) {
    int s = v.originDepartureSecsLocal();
    return s < 0 ? "n/a" : LocalTime.ofSecondOfDay(s % 86400).toString();
  }

  private static boolean isExpired(Resolution r, Instant now) {
    long ttl = r.tripId().isBlank() ? RESOLUTION_NEG_TTL_SECONDS : RESOLUTION_POS_TTL_SECONDS;
    return Duration.between(r.resolvedAt(), now).getSeconds() > ttl;
  }

  private Estimates updateTracks(List<EnrichedVehicle> enriched) {
    // 1. Ingest this poll's fixes into per-vehicle trajectories (keyed by operator+VehicleRef —
    //    a bare VehicleRef/fleet number isn't unique across operators).
    for (EnrichedVehicle ev : enriched) {
      if (ev.pos().id().isBlank()) {
        continue; // can't accumulate a trajectory without a stable identity
      }
      String vref = vehicleKey(ev.pos());
      VehicleTrack tr = tracks.computeIfAbsent(vref, k -> new VehicleTrack());
      if (!tr.tripId.equals(ev.tripId())) {
        tr.fixes.clear(); // new journey on this vehicle — start a fresh trajectory
        tr.tripId = ev.tripId();
      }
      tr.ingest(ev.pos());
    }

    // 2. Evict tracks we haven't seen for a while (vehicle left the bbox / ended its journey).
    Instant now = Instant.now();
    tracks
        .entrySet()
        .removeIf(
            e -> Duration.between(e.getValue().lastFixAt, now).getSeconds() > RETENTION_SECONDS);

    Map<String, RouteSnapper.Snap> snappedByVehicle = new HashMap<>();
    Set<String> activeTrips = new HashSet<>();
    for (Map.Entry<String, VehicleTrack> e : tracks.entrySet()) {
      VehicleTrack tr = e.getValue();
      if (tr.tripId.isBlank()) {
        continue;
      }
      activeTrips.add(tr.tripId);

      // Snap the newest fix onto the trip's road geometry. The shape is fetched lazily off-thread
      // the
      // first time we see the trip; until it lands, the bus stays at raw GPS. A snap further than
      // the
      // gate is left raw (likely a real diversion, not GPS noise). The prior chainage keeps a fix
      // near
      // a self-overlap on the lobe the bus was already on.
      RouteSnapper.Route shape = routeShapes.get(tr.tripId);
      if (shape == null) {
        requestShape(tr.tripId);
      } else {
        VehiclePosition newest = tr.fixes.peekLast();
        if (newest != null) {
          RouteSnapper.snap(shape, newest.lat(), newest.lng(), tr.lastSnappedS)
              .filter(s -> s.distMetres() <= SNAP_GATE_M)
              .ifPresent(
                  s -> {
                    snappedByVehicle.put(e.getKey(), s);
                    tr.lastSnappedS = s.chainage();
                  });
        }
      }
    }
    // Each vehicle's previous fix (one back in the buffer), for ALL tracked vehicles — matched or
    // not —
    // so a freshly-subscribed client can seed forward-prediction from two real fixes on the first
    // frame.
    Map<String, VehiclePosition> prevByVehicle = new HashMap<>();
    for (Map.Entry<String, VehicleTrack> e : tracks.entrySet()) {
      Iterator<VehiclePosition> it = e.getValue().fixes.descendingIterator();
      if (it.hasNext()) {
        it.next(); // skip the newest (that's the current fix)
        if (it.hasNext()) {
          prevByVehicle.put(e.getKey(), it.next()); // the one before it
        }
      }
    }

    // Bound the shape caches to trips present now (ended journeys fall out, mirroring the trip
    // cache).
    routeShapes.keySet().retainAll(activeTrips);
    shapeAttempted.retainAll(activeTrips);
    return new Estimates(Map.copyOf(snappedByVehicle), Map.copyOf(prevByVehicle));
  }

  /**
   * Kicks off a one-off background fetch of a trip's road geometry (deduped via {@code
   * shapeAttempted} so we don't re-queue it every poll). Off the poller thread because shapeless
   * trips hit OSRM.
   */
  private void requestShape(String tripId) {
    if (!shapeAttempted.add(tripId)) {
      return; // already fetched or in flight
    }
    try {
      shapeFetcher.submit(() -> fetchShape(tripId));
    } catch (RejectedExecutionException ignored) {
      shapeAttempted.remove(tripId); // executor shutting down — let a later poll retry
    }
  }

  /**
   * Loads + prepares a trip's road geometry into the snap cache. A trip with no usable geometry is
   * left absent (kept in {@code shapeAttempted} so we don't refetch until it leaves the active
   * set); a transient failure is un-marked so the next poll retries. Runs on the shape-fetcher
   * thread.
   */
  private void fetchShape(String tripId) {
    try {
      List<LatLng> shape = timetables.shapeForTrip(tripId);
      RouteSnapper.Route route = new RouteSnapper.Route(shape);
      if (route.usable()) {
        routeShapes.put(tripId, route);
      }
    } catch (StorageException | RuntimeException ex) {
      shapeAttempted.remove(tripId);
      log.debug("Route-shape fetch failed for trip {}: {}", tripId, ex.toString());
    }
  }

  /**
   * Stable per-vehicle identity: VehicleRef is operator-scoped, so qualify it with the operator.
   */
  static String vehicleKey(VehiclePosition v) {
    return v.operator() + '\0' + v.id();
  }
}
