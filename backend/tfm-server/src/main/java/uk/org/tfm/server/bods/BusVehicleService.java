package uk.org.tfm.server.bods;

import static uk.org.tfm.server.bods.BusVehicleProcessor.vehicleKey;

import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.vehicle.VehiclePosition;
import uk.org.tfm.proto.v1.BusVehicle;
import uk.org.tfm.proto.v1.GetBusVehiclesResponse;
import uk.org.tfm.proto.v1.StreamBusVehiclesRequest;
import uk.org.tfm.server.bods.BusVehicleProcessor.EnrichedVehicle;
import uk.org.tfm.server.bods.BusVehicleProcessor.Snapshot;
import uk.org.tfm.server.config.Config;
import uk.org.tfm.server.service.BusVehicleFeedStatus;
import uk.org.tfm.server.stream.BroadcastHub;

/** Atomic bus snapshot reads, freshness/viewport filtering, and browser stream fan-out. */
@Singleton
public final class BusVehicleService implements BusVehicleFeedStatus, AutoCloseable {
  private final boolean enabled;
  private final long stalenessSeconds;
  private final AtomicReference<Snapshot> snapshot =
      new AtomicReference<>(new Snapshot(List.of(), Map.of(), Map.of(), null));
  // Fan-out for the StreamBusVehicles server-stream: each subscriber gets the snapshot filtered to
  // its
  // own viewport (+ its tracked trip), pushed on subscribe and after every poll.
  private final BroadcastHub<StreamBusVehiclesRequest, GetBusVehiclesResponse> hub =
      new BroadcastHub<>(
          "bus-vehicles",
          req ->
              List.of(
                  currentResponse(
                      req.getMinLat(),
                      req.getMinLng(),
                      req.getMaxLat(),
                      req.getMaxLng(),
                      req.getTrackedTripId())));

  @Inject
  public BusVehicleService(Config config) {
    enabled = config.busVehiclesEnabled();
    stalenessSeconds = config.bodsStalenessSeconds();
  }

  void publish(Snapshot next) {
    snapshot.set(next);
    hub.broadcast();
  }

  @Override
  public void close() {
    hub.shutdown();
  }

  /**
   * Registers a live-bus-positions stream subscription (the {@code StreamBusVehicles} RPC). The hub
   * sends the current snapshot immediately and after every poll; the client replaces its state per
   * message. Auto-unsubscribes on cancel/error.
   */
  public void subscribe(
      StreamObserver<GetBusVehiclesResponse> observer, StreamBusVehiclesRequest req) {
    hub.subscribe(observer, req);
  }

  // ---- DataSourceRegistry accessors (read-only, lock-free snapshot reads) ----

  /** Whether the BODS bus-vehicle poller is configured (BODS_API_KEY present). */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * The instant the last successful poll completed, or empty if no poll has succeeded yet. Safe to
   * call from any thread — reads the atomic snapshot reference.
   */
  public Optional<Instant> lastFetchedAt() {
    return Optional.ofNullable(snapshot.get().fetchedAt());
  }

  /**
   * Number of vehicles in the current snapshot. Returns 0 if no poll has succeeded yet. Safe to
   * call from any thread.
   */
  public int vehicleCount() {
    return snapshot.get().vehicles().size();
  }

  /**
   * Whether the current snapshot is stale (never fetched, or fetchedAt is older than {@code
   * bodsStalenessSeconds}). A stale snapshot means the bus feed has stopped updating.
   */
  public boolean isStale() {
    Snapshot snap = snapshot.get();
    if (snap.fetchedAt() == null) {
      return true;
    }
    return Duration.between(snap.fetchedAt(), Instant.now()).getSeconds() > stalenessSeconds;
  }

  /** Builds the current response: fresh vehicles, snapshot age, and a staleness flag. */
  public GetBusVehiclesResponse currentResponse() {
    return currentResponse(0, 0, 0, 0); // degenerate box → all buses
  }

  /**
   * As {@link #currentResponse()} but filtered to a viewport bounding box. A degenerate box
   * (minLat>=maxLat or minLng>=maxLng, e.g. all-zero) means "all buses" for back-compat. The full
   * feed always stays on the server; this only narrows what a given client receives.
   */
  public GetBusVehiclesResponse currentResponse(
      double minLat, double minLng, double maxLat, double maxLng) {
    return currentResponse(minLat, minLng, maxLat, maxLng, "");
  }

  /**
   * As {@link #currentResponse(double, double, double, double)} but also always includes the
   * vehicle operating {@code trackedTripId} (when present in the feed) even if it's outside the box
   * — so a service picked from a board stays locatable/followable while its bus is off-screen. "" =
   * none.
   */
  public GetBusVehiclesResponse currentResponse(
      double minLat, double minLng, double maxLat, double maxLng, String trackedTripId) {
    boolean filter = minLat < maxLat && minLng < maxLng;
    boolean track = trackedTripId != null && !trackedTripId.isBlank();
    Snapshot snap = snapshot.get();
    Instant now = Instant.now();
    boolean haveData = snap.fetchedAt() != null;
    long ageSeconds =
        haveData ? Math.max(0, Duration.between(snap.fetchedAt(), now).getSeconds()) : 0;
    boolean stale = !haveData || ageSeconds > stalenessSeconds;

    GetBusVehiclesResponse.Builder out =
        GetBusVehiclesResponse.newBuilder()
            .setGeneratedAt(haveData ? snap.fetchedAt().toString() : "")
            .setSnapshotAgeSeconds((int) Math.min(ageSeconds, Integer.MAX_VALUE))
            .setStale(stale);

    // Dedupe by operator+VehicleRef, keeping the freshest fix: the feed can carry a vehicle twice,
    // which would otherwise render as overlapping markers / collide on the client's React keys.
    Map<String, EnrichedVehicle> unique = new HashMap<>();
    for (EnrichedVehicle ev : snap.vehicles()) {
      VehiclePosition v = ev.pos();
      // Per-vehicle freshness: drop buses whose last fix is older than the threshold.
      if (Duration.between(v.recordedAt(), now).getSeconds() > stalenessSeconds) {
        continue;
      }
      // Viewport cull: keep buses inside the requested box, plus the tracked trip's bus wherever it
      // is.
      boolean inBox =
          !filter
              || (v.lat() >= minLat && v.lat() <= maxLat && v.lng() >= minLng && v.lng() <= maxLng);
      if (!inBox && !(track && trackedTripId.equals(ev.tripId()))) {
        continue;
      }
      EnrichedVehicle prev = unique.get(vehicleKey(v));
      if (prev == null || v.recordedAt().isAfter(prev.pos().recordedAt())) {
        unique.put(vehicleKey(v), ev);
      }
    }
    Map<String, RouteSnapper.Snap> snapped = snap.snappedByVehicle();
    Map<String, VehiclePosition> prevFix = snap.prevByVehicle();
    for (EnrichedVehicle ev : unique.values()) {
      VehiclePosition v = ev.pos();
      BusVehicle.Builder b =
          BusVehicle.newBuilder()
              .setId(v.id())
              .setLine(v.line())
              .setDestination(v.destination())
              .setOperator(v.operator())
              .setLat(v.lat())
              .setLng(v.lng())
              .setBearing(v.bearing())
              .setRecordedAt(v.recordedAt().equals(Instant.EPOCH) ? "" : v.recordedAt().toString())
              .setTripId(ev.tripId());
      VehiclePosition pf = prevFix.get(vehicleKey(v));
      if (pf != null) {
        b.setPrevLat(pf.lat())
            .setPrevLng(pf.lng())
            .setPrevRecordedAt(
                pf.recordedAt().equals(Instant.EPOCH) ? "" : pf.recordedAt().toString());
      }
      RouteSnapper.Snap sp = snapped.get(vehicleKey(v));
      if (sp != null) {
        b.setSnapped(true)
            .setSnappedLat(sp.lat())
            .setSnappedLng(sp.lng())
            .setSnappedBearing(sp.bearing());
      }
      out.addVehicles(b.build());
    }
    return out.build();
  }
}
