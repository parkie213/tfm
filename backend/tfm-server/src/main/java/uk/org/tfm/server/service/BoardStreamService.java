package uk.org.tfm.server.service;

import io.grpc.stub.StreamObserver;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.proto.v1.GetBusDeparturesRequest;
import uk.org.tfm.proto.v1.GetBusDeparturesResponse;
import uk.org.tfm.proto.v1.GetStationBoardRequest;
import uk.org.tfm.proto.v1.GetStationBoardResponse;
import uk.org.tfm.proto.v1.GetTrainArrivalsRequest;
import uk.org.tfm.proto.v1.GetTrainArrivalsResponse;
import uk.org.tfm.proto.v1.GetTrainDeparturesRequest;
import uk.org.tfm.proto.v1.GetTrainDeparturesResponse;
import uk.org.tfm.server.stream.BroadcastHub;

/**
 * Hosts the server-streamed departure boards: the server keeps each <em>watched</em> board fresh on
 * a timer and fans changes out, instead of every client polling its own copy. Upstream load (Darwin
 * via the cached {@link TrainBoardService}; GTFS via {@link BusTimetableService}) is demand-driven
 * — a board's frame function runs only while it has ≥1 subscriber, and at most once per refresh
 * regardless of how many clients watch the same stop.
 *
 * <p>Each board reuses its unary request/response message; the streamed frame is exactly what the
 * unary RPC would return, so the client just swaps a poll for a subscription.
 */
@Singleton
public final class BoardStreamService {

  private static final Logger log = LoggerFactory.getLogger(BoardStreamService.class);
  private static final String DEFAULT_CRS = "MMO"; // Melton Mowbray
  private static final long REFRESH_SECONDS = 15;

  private final BroadcastHub<GetStationBoardRequest, GetStationBoardResponse> stationBoard;
  private final BroadcastHub<GetTrainDeparturesRequest, GetTrainDeparturesResponse> trainDep;
  private final BroadcastHub<GetTrainArrivalsRequest, GetTrainArrivalsResponse> trainArr;
  private final BroadcastHub<GetBusDeparturesRequest, GetBusDeparturesResponse> busDep;

  @Inject
  public BoardStreamService(
      TrainBoardService trains, BusTimetableService timetables, StationBoardService stations) {
    this.stationBoard =
        new BroadcastHub<>(
            "station-board",
            req -> List.of(stations.getBoard(crs(req.getCrs()), req.getNumRows())));
    // Train boards: cached in TrainBoardService, so re-querying per refresh hits Darwin at most
    // once
    // per cache TTL. A Darwin hiccup throws → the hub skips the frame and the client keeps its last
    // good board (better than blanking it).
    this.trainDep =
        new BroadcastHub<>(
            "train-departures",
            req -> List.of(trains.getDepartures(crs(req.getCrs()), req.getNumRows())));
    this.trainArr =
        new BroadcastHub<>(
            "train-arrivals",
            req -> List.of(trains.getArrivals(crs(req.getCrs()), req.getNumRows())));
    // Bus board: scheduled times from GTFS. Wrap the checked SQL exception so a transient DB error
    // skips the frame rather than killing the stream.
    this.busDep =
        new BroadcastHub<>(
            "bus-departures",
            req -> {
              try {
                return List.of(
                    timetables.getDepartures(
                        req.getAtcoCode().trim(),
                        req.getNumRows(),
                        req.getLookaheadMins(),
                        req.getDate()));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
  }

  public void start() {
    stationBoard.startPeriodic(REFRESH_SECONDS);
    trainDep.startPeriodic(REFRESH_SECONDS);
    trainArr.startPeriodic(REFRESH_SECONDS);
    busDep.startPeriodic(REFRESH_SECONDS);
    log.info("Board stream service started (refresh {}s).", REFRESH_SECONDS);
  }

  public void stop() {
    stationBoard.shutdown();
    trainDep.shutdown();
    trainArr.shutdown();
    busDep.shutdown();
  }

  public void subscribeStationBoard(
      StreamObserver<GetStationBoardResponse> obs, GetStationBoardRequest req) {
    stationBoard.subscribe(obs, req);
  }

  public void subscribeTrainDepartures(
      StreamObserver<GetTrainDeparturesResponse> obs, GetTrainDeparturesRequest req) {
    trainDep.subscribe(obs, req);
  }

  public void subscribeTrainArrivals(
      StreamObserver<GetTrainArrivalsResponse> obs, GetTrainArrivalsRequest req) {
    trainArr.subscribe(obs, req);
  }

  public void subscribeBusDepartures(
      StreamObserver<GetBusDeparturesResponse> obs, GetBusDeparturesRequest req) {
    busDep.subscribe(obs, req);
  }

  private static String crs(String raw) {
    return raw.isBlank() ? DEFAULT_CRS : raw.toUpperCase();
  }
}
