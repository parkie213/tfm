package uk.org.tfm.server.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.feed.FeedHealth;
import uk.org.tfm.model.feed.TrainBoardFeedHealth;
import uk.org.tfm.model.storage.LocalityCountSource;
import uk.org.tfm.model.storage.StopCountSource;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.storage.TripCountSource;
import uk.org.tfm.proto.v1.DataSource;
import uk.org.tfm.proto.v1.DataSourceStatus;
import uk.org.tfm.server.config.Config;

/**
 * Assembles a point-in-time inventory of every external data source the app consumes, drawn purely
 * from in-process state already held by the service singletons. No outbound calls are made.
 *
 * <p>DB row-counts (trips, stops, localities) are TTL-cached in this class — a 5-minute window
 * avoids per-request {@code COUNT(*)} scans while keeping the numbers reasonably fresh.
 */
@Singleton
public final class DataSourceRegistry {

  private static final Logger log = LoggerFactory.getLogger(DataSourceRegistry.class);

  // TTL for DB row-count caches (trips, stops, localities).
  private static final Duration COUNT_CACHE_TTL = Duration.ofMinutes(5);

  // The Darwin push-port topic carries many messages a second nationally; this long a silence means
  // the consumer has stopped (e.g. its thread died), not that the network went quiet.
  static final Duration PUSHPORT_STALE_AFTER = Duration.ofMinutes(5);

  private final Config config;
  private final BusVehicleFeedStatus busVehicleFeedStatus;
  private final PlatformFeedInfo platformFeedInfo;
  private final GtfsFeedInfo gtfsFeedInfo;
  private final LoadStateInfo loadStateInfo;
  private final TripCountSource tripCountSource;
  private final StopCountSource stopCountSource;
  private final LocalityCountSource localityCountSource;
  private final NaptanLoaderInfo naptanLoaderInfo;
  private final NptgLoaderInfo nptgLoaderInfo;
  private final OsrmInfo osrmInfo;
  private final TrainBoardFeedHealth trainBoards;

  // ---- Tiny TTL-cached count holders ----

  private volatile long cachedTripCount = -1;
  private volatile Instant tripCountAt = Instant.EPOCH;

  private volatile long cachedStopCount = -1;
  private volatile Instant stopCountAt = Instant.EPOCH;

  private volatile long cachedLocalityCount = -1;
  private volatile Instant localityCountAt = Instant.EPOCH;

  // OSRM reachability is an HTTP probe, so cache it (≤ one probe per TTL on the describe path).
  private volatile boolean cachedOsrmReachable = false;
  private volatile Instant osrmProbeAt = Instant.EPOCH;

  @Inject
  public DataSourceRegistry(
      Config config,
      BusVehicleFeedStatus busVehicleFeedStatus,
      PlatformFeedInfo platformFeedInfo,
      GtfsFeedInfo gtfsFeedInfo,
      LoadStateInfo loadStateInfo,
      TripCountSource tripCountSource,
      StopCountSource stopCountSource,
      LocalityCountSource localityCountSource,
      NaptanLoaderInfo naptanLoaderInfo,
      NptgLoaderInfo nptgLoaderInfo,
      OsrmInfo osrmInfo,
      TrainBoardFeedHealth trainBoards) {
    this.config = config;
    this.busVehicleFeedStatus = busVehicleFeedStatus;
    this.platformFeedInfo = platformFeedInfo;
    this.gtfsFeedInfo = gtfsFeedInfo;
    this.loadStateInfo = loadStateInfo;
    this.tripCountSource = tripCountSource;
    this.stopCountSource = stopCountSource;
    this.localityCountSource = localityCountSource;
    this.naptanLoaderInfo = naptanLoaderInfo;
    this.nptgLoaderInfo = nptgLoaderInfo;
    this.osrmInfo = osrmInfo;
    this.trainBoards = trainBoards;
  }

  /**
   * Returns a snapshot of all known data sources with their current status. Each helper is
   * defensive — a failure in one never prevents the others from being returned.
   */
  public List<DataSource> describe() {
    return List.of(
        buildRdmLdbws(),
        buildRdmArrivals(),
        buildDarwinPushport(),
        buildBodsSiriVm(),
        buildGtfsEastMidlands(),
        buildOsrm(),
        buildBasemap(),
        buildNaptan(),
        buildNptg());
  }

  // ---- Trains ----

  private DataSource buildRdmLdbws() {
    return buildBoardFeed(
        trainBoards.departures(),
        "rdm-ldbws",
        "RDM LDBWS",
        "Live train departure boards",
        "RDM consumer key required (DARWIN_TOKEN)",
        "RDM LDBWS departure boards");
  }

  private DataSource buildRdmArrivals() {
    return buildBoardFeed(
        trainBoards.arrivals(),
        "rdm-arrivals",
        "RDM arrivals board",
        "Live train arrivals",
        "separate RDM product; set DARWIN_ARRIVALS_BASE_URL to enable",
        "RDM arrivals board");
  }

  /**
   * An on-demand request/response API: status reflects the OUTCOME of the most recent fetch, not a
   * cadence (so a quiet period isn't mislabelled stale). expected_interval=0 → neutral dot; the
   * badge + detail carry the truth.
   */
  private static DataSource buildBoardFeed(
      FeedHealth health,
      String id,
      String name,
      String feeds,
      String disabledDetail,
      String liveDetail) {
    Optional<Instant> ok = health.lastSuccessAt();
    Optional<Instant> err = health.lastErrorAt();
    boolean errIsLatest = err.isPresent() && (ok.isEmpty() || err.get().isAfter(ok.get()));

    DataSourceStatus status;
    String detail;
    if (!health.configured()) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_DISABLED;
      detail = disabledDetail;
    } else if (ok.isEmpty() && err.isEmpty()) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_SCAFFOLDED;
      detail = "configured; no board fetched yet this session";
    } else if (errIsLatest) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_ERROR;
      detail = "last board fetch failed: " + health.lastError().orElse("unknown");
    } else {
      status = DataSourceStatus.DATA_SOURCE_STATUS_LIVE;
      detail = liveDetail;
    }
    return DataSource.newBuilder()
        .setId(id)
        .setName(name)
        .setCategory("Trains")
        .setFeeds(feeds)
        .setStatus(status)
        .setLastUpdated(ok.map(Instant::toString).orElse(""))
        .setExpectedIntervalSecs(0)
        .setItemCount(-1)
        .setDetail(detail)
        .build();
  }

  private DataSource buildDarwinPushport() {
    boolean enabled = platformFeedInfo.isEnabled();

    long itemCount = -1;
    try {
      itemCount = platformFeedInfo.platformIndexSize();
    } catch (Exception e) {
      log.debug("DataSourceRegistry: platform index size read failed", e);
    }

    // LIVE while messages are flowing; ERROR once an enabled consumer has gone silent past
    // PUSHPORT_STALE_AFTER (it has stopped — "enabled" alone can't reveal a dead consumer thread);
    // DISABLED when not configured, or handed to another environment via the kill-switch.
    Optional<Instant> lastMessage = platformFeedInfo.lastMessageAt();
    boolean stale =
        lastMessage.isPresent()
            && Duration.between(lastMessage.get(), Instant.now()).compareTo(PUSHPORT_STALE_AFTER)
                > 0;
    DataSourceStatus status;
    if (!enabled) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_DISABLED;
    } else if (stale) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_ERROR;
    } else {
      status = DataSourceStatus.DATA_SOURCE_STATUS_LIVE;
    }

    // When enabled, surface the last-message time so the freshness dot shows whether messages are
    // actually FLOWING — enabled but silent means the single-partition consumer group is likely
    // held
    // by another environment (starved), which "enabled" alone can't reveal.
    String lastUpdated = enabled ? lastMessage.map(Instant::toString).orElse("") : "";
    String detail;
    if (!enabled) {
      detail =
          "off — needs DARWIN_KAFKA_* creds and DARWIN_KAFKA_ENABLED=true "
              + "(kill-switch: deploy/darwin-feed.sh or the Darwin Kafka consumer workflow).";
    } else if (stale) {
      detail =
          "no message for over "
              + PUSHPORT_STALE_AFTER.toMinutes()
              + " min — the consumer has stopped; restart the server to reconnect.";
    } else if (lastMessage.isPresent()) {
      detail =
          "consuming the RDM Darwin Kafka topic (single partition; one environment at a time).";
    } else {
      detail =
          "enabled but no message received yet — the partition may be held by another "
              + "environment (release it there: darwin-feed.sh off / the Kafka consumer workflow).";
    }

    return DataSource.newBuilder()
        .setId("darwin-pushport")
        .setName("Darwin push-port timetable")
        .setCategory("Trains")
        .setFeeds("Live platform occupancy")
        .setStatus(status)
        .setLastUpdated(lastUpdated)
        .setExpectedIntervalSecs(30)
        .setItemCount(itemCount)
        .setDetail(detail)
        .build();
  }

  // ---- Buses ----

  private DataSource buildBodsSiriVm() {
    boolean enabled = busVehicleFeedStatus.isEnabled();
    DataSourceStatus status;
    if (!enabled) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_DISABLED;
    } else if (busVehicleFeedStatus.isStale()) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_ERROR;
    } else {
      status = DataSourceStatus.DATA_SOURCE_STATUS_LIVE;
    }

    String lastUpdated = "";
    long itemCount = -1;
    try {
      lastUpdated = busVehicleFeedStatus.lastFetchedAt().map(Instant::toString).orElse("");
      itemCount = busVehicleFeedStatus.vehicleCount();
    } catch (Exception e) {
      log.debug("DataSourceRegistry: BODS state read failed", e);
    }

    return DataSource.newBuilder()
        .setId("bods-siri-vm")
        .setName("BODS SIRI-VM")
        .setCategory("Buses")
        .setFeeds("Live bus vehicle positions")
        .setStatus(status)
        .setLastUpdated(lastUpdated)
        .setExpectedIntervalSecs(config.bodsPollSeconds())
        .setItemCount(itemCount)
        .setDetail("BODS open data; requires BODS_API_KEY")
        .build();
  }

  private DataSource buildGtfsEastMidlands() {
    boolean enabled = gtfsFeedInfo.isEnabled();
    String lastUpdated = "";
    long itemCount = -1;
    String detail = String.join(", ", gtfsFeedInfo.getRegions());
    boolean loaded = false;

    try {
      LoadStateInfo.FeedState state = loadStateInfo.read();
      if (state.feedLoadedAt() != null) {
        lastUpdated = state.feedLoadedAt().toString();
        loaded = true;
      }
      if (state.feedVersion() != null && !state.feedVersion().isBlank()) {
        detail = detail + " | feed " + state.feedVersion();
      }
    } catch (Exception e) {
      log.debug("DataSourceRegistry: load state read failed", e);
    }

    // LIVE only once a feed is actually loaded into Postgres; enabled-but-not-loaded is SCAFFOLDED.
    // (No staleness check: a GTFS feed legitimately doesn't change for days/weeks, so age ≠ fault.)
    DataSourceStatus status;
    if (!enabled) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_DISABLED;
    } else if (loaded) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_LIVE;
    } else {
      status = DataSourceStatus.DATA_SOURCE_STATUS_SCAFFOLDED;
    }

    itemCount = cachedTripCount(itemCount);

    return DataSource.newBuilder()
        .setId("gtfs-east-midlands")
        .setName("GTFS (East Midlands)")
        .setCategory("Buses")
        .setFeeds("Bus timetables, routes & shapes")
        .setStatus(status)
        .setLastUpdated(lastUpdated)
        .setExpectedIntervalSecs(86400)
        .setItemCount(itemCount)
        .setDetail(detail)
        .build();
  }

  // ---- Geography & routing ----

  private DataSource buildOsrm() {
    boolean enabled = osrmInfo.enabled();
    String baseUrl = config.osrmUrl();
    String detail = baseUrl;
    if (baseUrl.contains("router.project-osrm.org")) {
      detail = detail + " (public demo — dev only)";
    }

    // Verify reachability, not just "URL configured" — but cache the probe (it's an HTTP call).
    DataSourceStatus status;
    if (!enabled) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_DISABLED;
    } else if (osrmReachable()) {
      status = DataSourceStatus.DATA_SOURCE_STATUS_LIVE;
    } else {
      status = DataSourceStatus.DATA_SOURCE_STATUS_ERROR;
      detail = detail + " — not reachable";
    }

    return DataSource.newBuilder()
        .setId("osrm")
        .setName("OSRM")
        .setCategory("Geography & routing")
        .setFeeds("Road-snapping for bus routes without GTFS shapes")
        .setStatus(status)
        .setLastUpdated("")
        .setExpectedIntervalSecs(0)
        .setItemCount(-1)
        .setDetail(detail)
        .build();
  }

  private DataSource buildBasemap() {
    return DataSource.newBuilder()
        .setId("openfreemap")
        .setName("OpenFreeMap basemap")
        .setCategory("Geography & routing")
        .setFeeds("Base map style and vector tiles (client-side)")
        .setStatus(DataSourceStatus.DATA_SOURCE_STATUS_LIVE)
        .setLastUpdated("")
        .setExpectedIntervalSecs(0)
        .setItemCount(-1)
        .setDetail("OpenFreeMap Liberty style; map data © OpenStreetMap contributors")
        .build();
  }

  // ---- Reference data ----

  private DataSource buildNaptan() {
    Optional<Instant> loadedAt = Optional.empty();
    long itemCount = -1;
    try {
      loadedAt = naptanLoaderInfo.lastLoadedAt();
    } catch (Exception e) {
      log.debug("DataSourceRegistry: NaPTAN loader info read failed", e);
    }
    itemCount = cachedStopCount(itemCount);
    // LIVE only once actually loaded; before that it's wired-but-not-yet-loaded, not live.
    DataSourceStatus status =
        loadedAt.isPresent()
            ? DataSourceStatus.DATA_SOURCE_STATUS_LIVE
            : DataSourceStatus.DATA_SOURCE_STATUS_SCAFFOLDED;

    return DataSource.newBuilder()
        .setId("naptan")
        .setName("NaPTAN")
        .setCategory("Reference data")
        .setFeeds("GB-wide bus/rail stops")
        .setStatus(status)
        .setLastUpdated(loadedAt.map(Instant::toString).orElse(""))
        .setExpectedIntervalSecs(86400)
        .setItemCount(itemCount)
        .setDetail("DfT open CSV (OGL); note: missing StopAreas")
        .build();
  }

  private DataSource buildNptg() {
    Optional<Instant> loadedAt = Optional.empty();
    long itemCount = -1;
    try {
      loadedAt = nptgLoaderInfo.lastLoadedAt();
    } catch (Exception e) {
      log.debug("DataSourceRegistry: NPTG loader info read failed", e);
    }
    itemCount = cachedLocalityCount(itemCount);
    DataSourceStatus status =
        loadedAt.isPresent()
            ? DataSourceStatus.DATA_SOURCE_STATUS_LIVE
            : DataSourceStatus.DATA_SOURCE_STATUS_SCAFFOLDED;

    return DataSource.newBuilder()
        .setId("nptg")
        .setName("NPTG localities")
        .setCategory("Reference data")
        .setFeeds("Locality names for stops")
        .setStatus(status)
        .setLastUpdated(loadedAt.map(Instant::toString).orElse(""))
        .setExpectedIntervalSecs(86400)
        .setItemCount(itemCount)
        .setDetail("DfT open CSV (OGL)")
        .build();
  }

  // ---- TTL-cached count helpers ----

  private long cachedTripCount(long fallback) {
    Instant now = Instant.now();
    if (Duration.between(tripCountAt, now).compareTo(COUNT_CACHE_TTL) < 0) {
      return cachedTripCount < 0 ? fallback : cachedTripCount;
    }
    try {
      cachedTripCount = tripCountSource.tripCount();
      tripCountAt = now;
      return cachedTripCount;
    } catch (StorageException e) {
      log.debug("DataSourceRegistry: gtfs_trips COUNT(*) failed", e);
      return fallback;
    }
  }

  private long cachedStopCount(long fallback) {
    Instant now = Instant.now();
    if (Duration.between(stopCountAt, now).compareTo(COUNT_CACHE_TTL) < 0) {
      return cachedStopCount < 0 ? fallback : cachedStopCount;
    }
    try {
      cachedStopCount = stopCountSource.activeStopCount();
      stopCountAt = now;
      return cachedStopCount;
    } catch (StorageException e) {
      log.debug("DataSourceRegistry: stops COUNT(*) failed", e);
      return fallback;
    }
  }

  private long cachedLocalityCount(long fallback) {
    Instant now = Instant.now();
    if (Duration.between(localityCountAt, now).compareTo(COUNT_CACHE_TTL) < 0) {
      return cachedLocalityCount < 0 ? fallback : cachedLocalityCount;
    }
    try {
      cachedLocalityCount = localityCountSource.localityCount();
      localityCountAt = now;
      return cachedLocalityCount;
    } catch (StorageException e) {
      log.debug("DataSourceRegistry: nptg_localities COUNT(*) failed", e);
      return fallback;
    }
  }

  /**
   * OSRM reachability, cached for {@link #COUNT_CACHE_TTL} so describe() does at most one
   * probe/TTL.
   */
  private boolean osrmReachable() {
    Instant now = Instant.now();
    if (Duration.between(osrmProbeAt, now).compareTo(COUNT_CACHE_TTL) < 0) {
      return cachedOsrmReachable;
    }
    try {
      cachedOsrmReachable = osrmInfo.isReachable();
    } catch (Exception e) {
      log.debug("DataSourceRegistry: OSRM reachability probe failed", e);
      cachedOsrmReachable = false;
    }
    osrmProbeAt = now;
    return cachedOsrmReachable;
  }
}
