package uk.org.tfm.server.service;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.proto.v1.GetBusBoardRequest;
import uk.org.tfm.proto.v1.GetBusBoardResponse;
import uk.org.tfm.proto.v1.GetBusDeparturesRequest;
import uk.org.tfm.proto.v1.GetBusDeparturesResponse;
import uk.org.tfm.proto.v1.GetBusVehiclesRequest;
import uk.org.tfm.proto.v1.GetBusVehiclesResponse;
import uk.org.tfm.proto.v1.GetDataSourcesRequest;
import uk.org.tfm.proto.v1.GetDataSourcesResponse;
import uk.org.tfm.proto.v1.GetMapDataStatusRequest;
import uk.org.tfm.proto.v1.GetMapDataStatusResponse;
import uk.org.tfm.proto.v1.GetNearbyStopsRequest;
import uk.org.tfm.proto.v1.GetNearbyStopsResponse;
import uk.org.tfm.proto.v1.GetRouteShapesInViewportRequest;
import uk.org.tfm.proto.v1.GetRouteShapesInViewportResponse;
import uk.org.tfm.proto.v1.GetRouteStopsRequest;
import uk.org.tfm.proto.v1.GetServiceStopsRequest;
import uk.org.tfm.proto.v1.GetServiceStopsResponse;
import uk.org.tfm.proto.v1.GetStationBoardRequest;
import uk.org.tfm.proto.v1.GetStationBoardResponse;
import uk.org.tfm.proto.v1.GetStationPlatformsRequest;
import uk.org.tfm.proto.v1.GetStationPlatformsResponse;
import uk.org.tfm.proto.v1.GetStopsInViewportRequest;
import uk.org.tfm.proto.v1.GetStopsInViewportResponse;
import uk.org.tfm.proto.v1.GetTrainArrivalsRequest;
import uk.org.tfm.proto.v1.GetTrainArrivalsResponse;
import uk.org.tfm.proto.v1.GetTrainDeparturesRequest;
import uk.org.tfm.proto.v1.GetTrainDeparturesResponse;
import uk.org.tfm.proto.v1.GetTrainServiceRequest;
import uk.org.tfm.proto.v1.GetTrainServiceResponse;
import uk.org.tfm.proto.v1.SearchStopsRequest;
import uk.org.tfm.proto.v1.SearchStopsResponse;
import uk.org.tfm.proto.v1.StreamBusVehiclesRequest;
import uk.org.tfm.proto.v1.TfmServiceGrpc;
import uk.org.tfm.server.bods.BusVehicleService;
import uk.org.tfm.server.config.Config;

/** gRPC entry point for transport boards, bus positions, search, and route geometry. */
@Singleton
public final class TfmServiceImpl extends TfmServiceGrpc.TfmServiceImplBase {

  private static final Logger log = LoggerFactory.getLogger(TfmServiceImpl.class);
  private static final String DEFAULT_CRS = "MMO"; // Melton Mowbray

  private final TrainBoardService trains;
  private final StationBoardService stationBoards;
  private final BusVehicleService busVehicles;
  private final BoardStreamService boardStreams;
  private final BusTimetableService timetables;
  private final BusBoardService busBoards;
  private final Config config;
  private final CatalogService catalog;
  private final TrainJourneyService journeys;
  private final StationPlatformService platforms;
  private final BusMapService busMap;
  private final DataSourceRegistry dataSourceRegistry;

  @Inject
  public TfmServiceImpl(
      TrainBoardService trains,
      StationBoardService stationBoards,
      BusVehicleService busVehicles,
      BoardStreamService boardStreams,
      BusTimetableService timetables,
      BusBoardService busBoards,
      CatalogService catalog,
      TrainJourneyService journeys,
      StationPlatformService platforms,
      BusMapService busMap,
      Config config,
      DataSourceRegistry dataSourceRegistry) {
    this.trains = trains;
    this.stationBoards = stationBoards;
    this.busVehicles = busVehicles;
    this.boardStreams = boardStreams;
    this.timetables = timetables;
    this.busBoards = busBoards;
    this.config = config;
    this.catalog = catalog;
    this.journeys = journeys;
    this.platforms = platforms;
    this.busMap = busMap;
    this.dataSourceRegistry = dataSourceRegistry;
  }

  /**
   * Returns a combined arrival/departure board, joining rows by upstream service ID. Blank CRS
   * defaults to Melton Mowbray (MMO). Departure details take precedence; arrivals fill missing
   * details. Unconfigured or failed arrivals leave a departures-only response with
   * arrivals_available=false. Returns UNIMPLEMENTED without departure credentials, and UNAVAILABLE
   * when the required departure board cannot be loaded.
   */
  @Override
  public void getStationBoard(
      GetStationBoardRequest request, StreamObserver<GetStationBoardResponse> observer) {
    if (!stationBoardsConfigured(observer)) return;
    String crs = request.getCrs().isBlank() ? DEFAULT_CRS : request.getCrs().trim().toUpperCase();
    try {
      observer.onNext(stationBoards.getBoard(crs, request.getNumRows()));
      observer.onCompleted();
    } catch (RuntimeException e) {
      log.warn("Station board failed for crs={}", crs, e);
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Station board unavailable.")
              .withCause(e)
              .asRuntimeException());
    }
  }

  /**
   * Subscribes to complete combined station-board snapshots using the same merge rules as
   * GetStationBoard. Attempts an initial snapshot, then refreshes every 15 seconds through the
   * upstream board caches. Failed frame computations skip that update without closing the stream;
   * clients may retain their last snapshot. Missing departure credentials return UNIMPLEMENTED.
   * Client cancellation removes the subscription.
   */
  @Override
  public void streamStationBoard(
      GetStationBoardRequest request, StreamObserver<GetStationBoardResponse> observer) {
    if (stationBoardsConfigured(observer)) boardStreams.subscribeStationBoard(observer, request);
  }

  private boolean stationBoardsConfigured(StreamObserver<GetStationBoardResponse> observer) {
    if (!config.darwinToken().isBlank()) return true;
    observer.onError(
        Status.UNIMPLEMENTED
            .withDescription("Train departures are not configured.")
            .asRuntimeException());
    return false;
  }

  /**
   * Returns the cached Darwin departure board; blank CRS defaults to MMO. A non-positive row count
   * uses the server default. Upstream failures return UNAVAILABLE.
   */
  @Override
  public void getTrainDepartures(
      GetTrainDeparturesRequest request,
      StreamObserver<GetTrainDeparturesResponse> responseObserver) {
    String crs = request.getCrs().isBlank() ? DEFAULT_CRS : request.getCrs().toUpperCase();
    try {
      responseObserver.onNext(trains.getDepartures(crs, request.getNumRows()));
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.warn("Train departures failed for crs={}", crs, e);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Train board unavailable: " + e.getMessage())
              .withCause(e)
              .asRuntimeException());
    }
  }

  /**
   * Returns the cached Darwin arrivals board; blank CRS defaults to MMO. Returns UNIMPLEMENTED when
   * the optional arrivals product is not configured, or UNAVAILABLE if the board cannot be loaded.
   */
  @Override
  public void getTrainArrivals(
      GetTrainArrivalsRequest request, StreamObserver<GetTrainArrivalsResponse> responseObserver) {
    if (!config.arrivalsEnabled()) {
      responseObserver.onError(
          Status.UNIMPLEMENTED
              .withDescription("Arrivals are not enabled (no RDM arrivals product configured).")
              .asRuntimeException());
      return;
    }
    String crs = request.getCrs().isBlank() ? DEFAULT_CRS : request.getCrs().toUpperCase();
    try {
      responseObserver.onNext(trains.getArrivals(crs, request.getNumRows()));
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.warn("Train arrivals failed for crs={}", crs, e);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Arrivals board unavailable: " + e.getMessage())
              .withCause(e)
              .asRuntimeException());
    }
  }

  /**
   * Returns the merged timetable for explicitly selected stops, with a boarding stop on each row.
   * Invalid selections return INVALID_ARGUMENT; disabled timetables return UNIMPLEMENTED. A failed
   * stop is listed in the partial response; failure of every selected stop returns UNAVAILABLE.
   */
  @Override
  public void getBusBoard(
      GetBusBoardRequest request, StreamObserver<GetBusBoardResponse> observer) {
    var normalized = busBoardRequest(request, observer);
    if (normalized == null) return;
    try {
      observer.onNext(busBoards.getBoard(normalized));
      observer.onCompleted();
    } catch (Exception e) {
      log.warn("Selected-stop bus board unavailable.", e);
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Bus board temporarily unavailable.")
              .withCause(e)
              .asRuntimeException());
    }
  }

  /**
   * Streams selected-stop boards initially and every 15 seconds. All-stop query failures skip an
   * update; partial failures are included in the response. Cancellation removes the subscription.
   */
  @Override
  public void streamBusBoard(
      GetBusBoardRequest request, StreamObserver<GetBusBoardResponse> observer) {
    var normalized = busBoardRequest(request, observer);
    if (normalized != null) boardStreams.subscribeBusBoard(observer, normalized);
  }

  private GetBusBoardRequest busBoardRequest(
      GetBusBoardRequest request, StreamObserver<GetBusBoardResponse> observer) {
    if (!config.busTimetableEnabled()) {
      observer.onError(
          Status.UNIMPLEMENTED
              .withDescription("Bus timetables are disabled.")
              .asRuntimeException());
      return null;
    }
    try {
      return BusBoardService.normalize(request);
    } catch (IllegalArgumentException e) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
      return null;
    }
  }

  /**
   * Returns scheduled bus departures for a required NaPTAN ATCO stop code. An empty date requests
   * upcoming departures; a supplied date selects that day's timetable. Returns UNIMPLEMENTED when
   * timetables are disabled, INVALID_ARGUMENT for a blank stop code, or UNAVAILABLE on a timetable
   * query failure.
   */
  @Override
  public void getBusDepartures(
      GetBusDeparturesRequest request, StreamObserver<GetBusDeparturesResponse> responseObserver) {
    if (!config.busTimetableEnabled()) {
      responseObserver.onError(
          Status.UNIMPLEMENTED
              .withDescription("Bus timetable is not enabled (BUS_TIMETABLE_ENABLED=false).")
              .asRuntimeException());
      return;
    }
    String atcoCode = request.getAtcoCode().trim();
    if (atcoCode.isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription("atco_code is required.").asRuntimeException());
      return;
    }
    try {
      GetBusDeparturesResponse response =
          timetables.getDepartures(
              atcoCode, request.getNumRows(), request.getLookaheadMins(), request.getDate());
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error("Bus departures failed for atco_code='{}'", atcoCode, e);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Bus timetable temporarily unavailable.")
              .withCause(e)
              .asRuntimeException());
    }
  }

  // ---- Streamed departure boards (server-maintained; replace the polls) ----

  /**
   * Subscribes to cached departure-board snapshots; blank CRS defaults to MMO. Attempts an initial
   * snapshot and refreshes every 15 seconds. Failed frame computations skip an update without
   * closing the stream. Returns UNIMPLEMENTED without departure credentials. Client cancellation
   * removes the subscription.
   */
  @Override
  public void streamTrainDepartures(
      GetTrainDeparturesRequest request,
      StreamObserver<GetTrainDeparturesResponse> responseObserver) {
    if (config.darwinToken().isBlank()) {
      responseObserver.onError(
          Status.UNIMPLEMENTED
              .withDescription("Train departures are not configured (DARWIN_TOKEN is missing).")
              .asRuntimeException());
      return;
    }
    boardStreams.subscribeTrainDepartures(responseObserver, request);
  }

  /**
   * Subscribes to cached arrivals-board snapshots; blank CRS defaults to MMO. Attempts an initial
   * snapshot and refreshes every 15 seconds. Failed frame computations skip an update without
   * closing the stream. Returns UNIMPLEMENTED without an arrivals product. Client cancellation
   * removes the subscription.
   */
  @Override
  public void streamTrainArrivals(
      GetTrainArrivalsRequest request, StreamObserver<GetTrainArrivalsResponse> responseObserver) {
    if (!config.arrivalsEnabled()) {
      responseObserver.onError(
          Status.UNIMPLEMENTED
              .withDescription("Arrivals are not enabled (no RDM arrivals product configured).")
              .asRuntimeException());
      return;
    }
    boardStreams.subscribeTrainArrivals(responseObserver, request);
  }

  /**
   * Subscribes to scheduled departures for one stop, using the unary request's date and row
   * options. Attempts an initial snapshot and refreshes every 15 seconds; failed frames are
   * skipped. Returns UNIMPLEMENTED when timetables are disabled or INVALID_ARGUMENT for a blank
   * ATCO code. Client cancellation removes the subscription.
   */
  @Override
  public void streamBusDepartures(
      GetBusDeparturesRequest request, StreamObserver<GetBusDeparturesResponse> responseObserver) {
    if (!config.busTimetableEnabled()) {
      responseObserver.onError(
          Status.UNIMPLEMENTED
              .withDescription("Bus timetable is not enabled (BUS_TIMETABLE_ENABLED=false).")
              .asRuntimeException());
      return;
    }
    if (request.getAtcoCode().trim().isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription("atco_code is required.").asRuntimeException());
      return;
    }
    boardStreams.subscribeBusDepartures(responseObserver, request);
  }

  /**
   * Returns the ordered calling points and available geometry of a GTFS trip. Returns UNIMPLEMENTED
   * when timetables are disabled, INVALID_ARGUMENT for a blank trip ID, or UNAVAILABLE on a
   * timetable query failure.
   */
  @Override
  public void getServiceStops(
      GetServiceStopsRequest request, StreamObserver<GetServiceStopsResponse> responseObserver) {
    if (!config.busTimetableEnabled()) {
      responseObserver.onError(
          Status.UNIMPLEMENTED
              .withDescription("Bus timetable is not enabled (BUS_TIMETABLE_ENABLED=false).")
              .asRuntimeException());
      return;
    }
    String tripId = request.getTripId().trim();
    if (tripId.isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription("trip_id is required.").asRuntimeException());
      return;
    }
    try {
      responseObserver.onNext(timetables.getServiceStops(tripId));
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error("Service stops failed for trip_id='{}'", tripId, e);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Service detail temporarily unavailable.")
              .withCause(e)
              .asRuntimeException());
    }
  }

  /**
   * Returns calling points and geometry for a representative trip of a named bus route. Long name
   * and operator disambiguate reused route numbers. Returns UNIMPLEMENTED when timetables are
   * disabled, INVALID_ARGUMENT for a blank short name, or UNAVAILABLE on failure.
   */
  @Override
  public void getRouteStops(
      GetRouteStopsRequest request, StreamObserver<GetServiceStopsResponse> responseObserver) {
    if (!config.busTimetableEnabled()) {
      responseObserver.onError(
          Status.UNIMPLEMENTED
              .withDescription("Bus timetable is not enabled (BUS_TIMETABLE_ENABLED=false).")
              .asRuntimeException());
      return;
    }
    String shortName = request.getRouteShortName().trim();
    if (shortName.isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("route_short_name is required.")
              .asRuntimeException());
      return;
    }
    String longName = request.getRouteLongName().trim();
    String operator = request.getOperator().trim();
    try {
      responseObserver.onNext(timetables.getRouteStops(shortName, longName, operator));
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error(
          "Route stops failed for route='{}' / '{}' / op='{}'", shortName, longName, operator, e);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Route detail temporarily unavailable.")
              .withCause(e)
              .asRuntimeException());
    }
  }

  /**
   * Returns the current bus snapshot without waiting for an upstream request. A valid bounding box
   * filters vehicles; a degenerate box returns all vehicles. The snapshot may be empty or stale.
   * Returns UNIMPLEMENTED when live buses are not configured.
   */
  @Override
  public void getBusVehicles(
      GetBusVehiclesRequest request, StreamObserver<GetBusVehiclesResponse> responseObserver) {
    if (!config.busVehiclesEnabled()) {
      responseObserver.onError(
          Status.UNIMPLEMENTED
              .withDescription("Live bus positions are not enabled (no BODS API key configured).")
              .asRuntimeException());
      return;
    }
    // Always succeeds with the current snapshot (possibly empty/stale); never blocks on upstream.
    // The box narrows what THIS client receives; a degenerate (all-zero) box returns all buses.
    responseObserver.onNext(
        busVehicles.currentResponse(
            request.getMinLat(), request.getMinLng(), request.getMaxLat(), request.getMaxLng()));
    responseObserver.onCompleted();
  }

  /**
   * Subscribes to current bus snapshots and subsequent poll updates. The bounding box filters
   * vehicles; a requested tracked trip is included even outside it when available. Returns
   * UNIMPLEMENTED when live buses are not configured. Client cancellation removes the subscription.
   */
  @Override
  public void streamBusVehicles(
      StreamBusVehiclesRequest request, StreamObserver<GetBusVehiclesResponse> responseObserver) {
    if (!config.busVehiclesEnabled()) {
      responseObserver.onError(
          Status.UNIMPLEMENTED
              .withDescription("Live bus positions are not enabled (no BODS API key configured).")
              .asRuntimeException());
      return;
    }
    // Long-lived server stream: the bus poller pushes the current snapshot now and after every
    // poll,
    // auto-unsubscribing on cancel. We never call onCompleted — the client closes the stream.
    busVehicles.subscribe(responseObserver, request);
  }

  /**
   * Radius/type filtering and distance ordering precede the cap; invalid queries fail explicitly.
   */
  @Override
  public void getNearbyStops(
      GetNearbyStopsRequest request, StreamObserver<GetNearbyStopsResponse> observer) {
    try {
      observer.onNext(
          catalog.nearby(
              request.getLat(),
              request.getLng(),
              request.getRadiusM(),
              request.getLimit(),
              request.getBusOnly()));
      observer.onCompleted();
    } catch (IllegalArgumentException e) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
    } catch (Exception e) {
      log.error("Nearby stop query failed", e);
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Stop data temporarily unavailable.")
              .asRuntimeException());
    }
  }

  /**
   * Returns catalogue stops inside the bounding box, with truncated=true if capped. Returns
   * INVALID_ARGUMENT for reversed, empty, or out-of-range bounds, and UNAVAILABLE on a catalogue
   * query failure.
   */
  @Override
  public void getStopsInViewport(
      GetStopsInViewportRequest request,
      StreamObserver<GetStopsInViewportResponse> responseObserver) {

    double minLat = request.getMinLat();
    double minLng = request.getMinLng();
    double maxLat = request.getMaxLat();
    double maxLng = request.getMaxLng();

    // Basic bbox sanity checks.
    if (minLat >= maxLat || minLng >= maxLng) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Invalid bounding box: min must be strictly less than max.")
              .asRuntimeException());
      return;
    }
    if (minLat < -90 || maxLat > 90 || minLng < -180 || maxLng > 180) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Bounding box coordinates out of range.")
              .asRuntimeException());
      return;
    }

    try {
      GetStopsInViewportResponse resp = catalog.stopsInViewport(minLat, minLng, maxLat, maxLng);
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error(
          "getStopsInViewport failed for bbox [{},{} -> {},{}]", minLat, minLng, maxLat, maxLng, e);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Stop data temporarily unavailable.")
              .asRuntimeException());
    }
  }

  /**
   * Returns shared bus-route geometry intersecting the bounding box, falling back to published GTFS
   * shapes when no shared trunks are found. Results may be capped. Returns INVALID_ARGUMENT for
   * invalid bounds or UNAVAILABLE on a spatial query failure.
   */
  @Override
  public void getRouteShapesInViewport(
      GetRouteShapesInViewportRequest request,
      StreamObserver<GetRouteShapesInViewportResponse> responseObserver) {

    double minLat = request.getMinLat();
    double minLng = request.getMinLng();
    double maxLat = request.getMaxLat();
    double maxLng = request.getMaxLng();

    // Basic bbox sanity checks (same contract as getStopsInViewport).
    if (minLat >= maxLat || minLng >= maxLng) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Invalid bounding box: min must be strictly less than max.")
              .asRuntimeException());
      return;
    }
    if (minLat < -90 || maxLat > 90 || minLng < -180 || maxLng > 180) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Bounding box coordinates out of range.")
              .asRuntimeException());
      return;
    }

    try {
      GetRouteShapesInViewportResponse resp =
          busMap.routesInViewport(minLat, minLng, maxLat, maxLng);
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error(
          "getRouteShapesInViewport failed for bbox [{},{} -> {},{}]",
          minLat,
          minLng,
          maxLat,
          maxLng,
          e);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Route data temporarily unavailable.")
              .asRuntimeException());
    }
  }

  /**
   * Returns stops and localities interleaved in relevance order, subject to the requested limit and
   * server cap. Queries shorter than two characters after trimming return an empty response. Query
   * failures return UNAVAILABLE.
   */
  @Override
  public void searchStops(
      SearchStopsRequest request, StreamObserver<SearchStopsResponse> responseObserver) {

    String query = request.getQuery().trim();

    // Return empty for blank or single-character queries; not an error.
    if (query.length() < 2) {
      responseObserver.onNext(SearchStopsResponse.getDefaultInstance());
      responseObserver.onCompleted();
      return;
    }

    int limit = request.getLimit();

    try {
      SearchStopsResponse resp = catalog.search(query, limit);
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error("searchStops failed for query='{}'", query, e);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Stop search temporarily unavailable.")
              .asRuntimeException());
    }
  }

  /**
   * Resolves a train-board row to stored Darwin calling points using station, scheduled time,
   * direction, and a destination hint. Returns found=false when no station or schedule can be
   * matched; that does not mean the train is cancelled. Unexpected failures return INTERNAL.
   */
  @Override
  public void getTrainService(
      GetTrainServiceRequest request, StreamObserver<GetTrainServiceResponse> responseObserver) {
    String crs = request.getCrs().trim().toUpperCase();
    String scheduledTime = request.getScheduledTime().trim();
    String destHint = request.getDestination().trim();
    boolean isArrival = request.getIsArrival();

    try {
      responseObserver.onNext(journeys.getService(crs, scheduledTime, destHint, isArrival));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      log.error(
          "getTrainService failed for crs='{}' time='{}' dest='{}'",
          crs,
          scheduledTime,
          destHint,
          e);
      responseObserver.onError(
          Status.INTERNAL.withDescription("Internal error").withCause(e).asRuntimeException());
    }
  }

  /**
   * Returns persisted platform inventory with reconciled REST board services and Kafka fallback
   * observations. REST IDs are not Darwin RIDs; REST presence remains unknown. Partial source
   * failures are reported in the response; failure of both sources returns INTERNAL. Empty
   * platforms do not establish physical absence, and Kafka state may be incomplete.
   */
  @Override
  public void getStationPlatforms(
      GetStationPlatformsRequest request,
      StreamObserver<GetStationPlatformsResponse> responseObserver) {
    String crs = request.getCrs().trim().toUpperCase();

    try {
      GetStationPlatformsResponse resp = platforms.getPlatforms(crs);
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      log.error("getStationPlatforms failed for crs='{}'", crs, e);
      responseObserver.onError(
          Status.INTERNAL.withDescription("Internal error").withCause(e).asRuntimeException());
    }
  }

  /**
   * Returns feed availability, freshness, and dataset counts without polling upstream transport
   * feeds. Individual source inspection failures are represented in the inventory; an unexpected
   * failure assembling the response returns INTERNAL.
   */
  @Override
  public void getDataSources(
      GetDataSourcesRequest request, StreamObserver<GetDataSourcesResponse> responseObserver) {
    try {
      GetDataSourcesResponse response =
          GetDataSourcesResponse.newBuilder()
              .addAllSources(dataSourceRegistry.describe())
              .setGeneratedAt(Instant.now().toString())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      log.error("getDataSources failed", e);
      responseObserver.onError(
          Status.INTERNAL.withDescription("Internal error").withCause(e).asRuntimeException());
    }
  }

  /**
   * Returns when derived bus-route geometry was last built, in epoch milliseconds. A zero timestamp
   * means it has never been built. Storage failures return UNAVAILABLE.
   */
  @Override
  public void getMapDataStatus(
      GetMapDataStatusRequest request, StreamObserver<GetMapDataStatusResponse> responseObserver) {
    try {
      responseObserver.onNext(busMap.status());
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.warn("getMapDataStatus failed", e);
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Map data status unavailable")
              .withCause(e)
              .asRuntimeException());
    }
  }
}
