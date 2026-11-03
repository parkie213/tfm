package uk.org.tfm.server.service;

import java.time.Duration;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.board.Board;
import uk.org.tfm.model.board.BoardProvider;
import uk.org.tfm.proto.v1.GetTrainArrivalsResponse;
import uk.org.tfm.proto.v1.GetTrainDeparturesResponse;
import uk.org.tfm.server.cache.ExpiringCache;

/**
 * Caches provider-independent live boards and maps them to the frontend contract. Provider JSON
 * stays inside the adapter.
 */
@Singleton
public final class TrainBoardService {

  private static final int DEFAULT_ROWS = 10;

  private final BoardProvider boards;
  private final ExpiringCache<String, GetTrainDeparturesResponse> depCache =
      new ExpiringCache<>(Duration.ofSeconds(30));
  private final ExpiringCache<String, GetTrainArrivalsResponse> arrCache =
      new ExpiringCache<>(Duration.ofSeconds(30));

  @Inject
  public TrainBoardService(BoardProvider boards) {
    this.boards = boards;
  }

  public GetTrainDeparturesResponse getDepartures(String crs, int numRows) {
    int rows = numRows > 0 ? numRows : DEFAULT_ROWS;
    return depCache.get(crs + ":" + rows, key -> loadDepartures(crs, rows));
  }

  public GetTrainArrivalsResponse getArrivals(String crs, int numRows) {
    int rows = numRows > 0 ? numRows : DEFAULT_ROWS;
    return arrCache.get(crs + ":" + rows, key -> loadArrivals(crs, rows));
  }

  private GetTrainDeparturesResponse loadDepartures(String crs, int rows) {
    Board board = boards.getDepartureBoard(crs, rows);
    GetTrainDeparturesResponse.Builder out =
        GetTrainDeparturesResponse.newBuilder()
            .setStationName(board.stationName())
            .setGeneratedAt(board.generatedAt())
            .setNrccMessages(board.messages());
    board.entries().forEach(entry -> out.addDepartures(BoardProtoMapper.toProto(entry)));

    return out.build();
  }

  private GetTrainArrivalsResponse loadArrivals(String crs, int rows) {
    Board board = boards.getArrivalBoard(crs, rows);
    GetTrainArrivalsResponse.Builder out =
        GetTrainArrivalsResponse.newBuilder()
            .setStationName(board.stationName())
            .setGeneratedAt(board.generatedAt())
            .setNrccMessages(board.messages());
    board.entries().forEach(entry -> out.addArrivals(BoardProtoMapper.toProto(entry)));

    return out.build();
  }
}
