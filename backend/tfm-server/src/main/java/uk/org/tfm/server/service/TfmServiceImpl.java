package uk.org.tfm.server.service;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.proto.v1.GetBusDeparturesRequest;
import uk.org.tfm.proto.v1.GetBusDeparturesResponse;
import uk.org.tfm.proto.v1.GetBusVehiclesRequest;
import uk.org.tfm.proto.v1.GetBusVehiclesResponse;
import uk.org.tfm.proto.v1.GetDataSourcesRequest;
import uk.org.tfm.proto.v1.GetDataSourcesResponse;
import uk.org.tfm.proto.v1.GetMapDataStatusRequest;
import uk.org.tfm.proto.v1.GetMapDataStatusResponse;
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
    this.config = config;
    this.catalog = catalog;
    this.journeys = journeys;
    this.platforms = platforms;
    this.busMap = busMap;
    this.dataSourceRegistry = dataSourceRegistry;
  }

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
   * Returns a point-in-time inventory of every external feed the application uses, assembled from
   * in-process state only — no outbound calls. Always succeeds: individual source failures degrade
   * to status ERROR / item_count -1 rather than failing the whole RPC.
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
   * When the derived bus route/trunk geometry was last (re)built — powers the client's "map data
   * updated N ago" hint. Returns 0 when it has never been built (fresh DB / nothing loaded yet).
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
