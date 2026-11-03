package uk.org.tfm.server.service;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.feed.TrainBoardFeedHealth;
import uk.org.tfm.proto.v1.Departure;
import uk.org.tfm.proto.v1.GetStationBoardResponse;
import uk.org.tfm.proto.v1.GetTrainArrivalsResponse;
import uk.org.tfm.proto.v1.StationBoardRow;
import uk.org.tfm.proto.v1.StationBoardTime;

/**
 * Joins the cached live arrivals and departures into one station board. Departure details take
 * precedence; arrivals supply missing details. Only non-blank upstream service IDs identify a join:
 * matching times or place names alone must not combine unrelated trains.
 */
@Singleton
public final class StationBoardService {
  private static final Logger log = LoggerFactory.getLogger(StationBoardService.class);
  private final TrainBoardService trains;
  private final TrainBoardFeedHealth feeds;

  @Inject
  public StationBoardService(TrainBoardService trains, TrainBoardFeedHealth feeds) {
    this.trains = trains;
    this.feeds = feeds;
  }

  /** Departures are required; missing or failed optional arrivals leave a departures-only board. */
  public GetStationBoardResponse getBoard(String crs, int numRows) {
    var departures = trains.getDepartures(crs, numRows);
    GetTrainArrivalsResponse arrivals = null;
    if (feeds.arrivals().configured()) {
      try {
        arrivals = trains.getArrivals(crs, numRows);
      } catch (RuntimeException e) {
        log.warn("Optional arrivals unavailable for station {}.", crs);
      }
    }

    List<StationBoardRow.Builder> rows = new ArrayList<>();
    Map<String, StationBoardRow.Builder> byService = new HashMap<>();
    for (Departure departure : departures.getDeparturesList()) {
      var row = details(departure).setDep(time(departure));
      rows.add(row);
      if (!departure.getServiceId().isBlank()) byService.put(departure.getServiceId(), row);
    }
    if (arrivals != null) {
      for (Departure arrival : arrivals.getArrivalsList()) {
        var row = arrival.getServiceId().isBlank() ? null : byService.get(arrival.getServiceId());
        if (row == null) {
          row = details(arrival);
          rows.add(row);
        } else {
          fillMissingDetails(row, arrival);
        }
        row.setArr(time(arrival));
      }
    }
    rows.sort(Comparator.comparingInt(StationBoardRow.Builder::getSortSecs));
    var result =
        GetStationBoardResponse.newBuilder()
            .setStationName(departures.getStationName())
            .setGeneratedAt(departures.getGeneratedAt())
            .setNrccMessages(departures.getNrccMessages())
            .setArrivalsAvailable(arrivals != null);
    for (var row : rows) result.addRows(row);
    return result.build();
  }

  private static StationBoardRow.Builder details(Departure service) {
    return StationBoardRow.newBuilder()
        .setServiceId(service.getServiceId())
        .setOrigin(service.getOrigin())
        .setDestination(service.getDestination())
        .setPlatform(service.getPlatformOrBay())
        .setOperator(service.getOperator())
        .setIsLive(service.getIsLive())
        .setCoachCount(service.getCoachCount())
        .addAllCoaches(service.getCoachesList())
        .setSortSecs(seconds(service.getScheduledTime()));
  }

  private static void fillMissingDetails(StationBoardRow.Builder row, Departure arrival) {
    if (row.getOrigin().isBlank()) row.setOrigin(arrival.getOrigin());
    if (row.getDestination().isBlank()) row.setDestination(arrival.getDestination());
    if (row.getPlatform().isBlank()) row.setPlatform(arrival.getPlatformOrBay());
    if (row.getOperator().isBlank()) row.setOperator(arrival.getOperator());
    row.setIsLive(row.getIsLive() || arrival.getIsLive());
    if (row.getCoachCount() == 0) row.setCoachCount(arrival.getCoachCount());
    if (row.getCoachesCount() == 0) row.addAllCoaches(arrival.getCoachesList());
  }

  private static StationBoardTime time(Departure service) {
    return StationBoardTime.newBuilder()
        .setScheduled(service.getScheduledTime())
        .setExpected(service.getExpectedTime())
        .setStatus(service.getStatus())
        .build();
  }

  private static int seconds(String value) {
    try {
      return LocalTime.parse(value).toSecondOfDay();
    } catch (DateTimeParseException e) {
      return Integer.MAX_VALUE;
    }
  }
}
