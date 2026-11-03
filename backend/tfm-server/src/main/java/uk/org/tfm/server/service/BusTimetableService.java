package uk.org.tfm.server.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.storage.TimetableStore;
import uk.org.tfm.model.timetable.JourneyDetails;
import uk.org.tfm.model.timetable.JourneyKey;
import uk.org.tfm.proto.v1.*;
import uk.org.tfm.server.db.OsrmRouter;

/** Composes stored bus schedules with optional road routing and maps them to the frontend API. */
@Singleton
public final class BusTimetableService {
  private final TimetableStore store;
  private final Function<List<double[]>, List<LatLng>> routeFallback;

  @Inject
  public BusTimetableService(TimetableStore store, OsrmRouter router) {
    this(store, router::snapToRoads);
  }

  BusTimetableService(TimetableStore store, Function<List<double[]>, List<LatLng>> routeFallback) {
    this.store = store;
    this.routeFallback = routeFallback;
  }

  public GetBusDeparturesResponse getDepartures(String stopId, int rows, int lookahead, String date)
      throws StorageException {
    var board = store.getDepartures(stopId, rows, lookahead, date);
    var out =
        GetBusDeparturesResponse.newBuilder()
            .setStopName(board.stopName())
            .setGeneratedAt(board.generatedAt())
            .setIsRealtime(false);
    for (var row : board.departures()) {
      out.addDepartures(
          Departure.newBuilder()
              .setScheduledTime(row.scheduledTime())
              .setLine(row.line())
              .setDestination(row.destination())
              .setOperator(row.operator())
              .setServiceId(row.tripId())
              .setIsLive(false)
              .setStatus("Scheduled"));
    }
    return out.build();
  }

  public Map<JourneyKey, String> matchTripIds(Collection<JourneyKey> journeys)
      throws StorageException {
    return store.matchTripIds(journeys);
  }

  public GetServiceStopsResponse getServiceStops(String tripId) throws StorageException {
    return toResponse(store.getServiceStops(tripId));
  }

  public GetServiceStopsResponse getRouteStops(String name, String longName, String operator)
      throws StorageException {
    return toResponse(store.getRouteStops(name, longName, operator));
  }

  public List<LatLng> shapeForTrip(String tripId) throws StorageException {
    return getServiceStops(tripId).getShapeList();
  }

  private GetServiceStopsResponse toResponse(JourneyDetails journey) {
    var out =
        GetServiceStopsResponse.newBuilder()
            .setLine(journey.line())
            .setDestination(journey.destination())
            .setOperator(journey.operator());
    for (var stop : journey.stops()) {
      out.addStops(
          ServiceStop.newBuilder()
              .setSequence(stop.sequence())
              .setAtcoCode(stop.atcoCode())
              .setName(stop.name())
              .setLocality(stop.locality())
              .setScheduledTime(stop.scheduledTime())
              .setLat(stop.lat())
              .setLng(stop.lng()));
    }
    for (var point : journey.shape()) {
      out.addShape(LatLng.newBuilder().setLat(point.lat()).setLng(point.lng()));
    }
    if (journey.shape().isEmpty()) {
      var coords =
          journey.stops().stream()
              .filter(s -> s.lat() != 0 || s.lng() != 0)
              .map(s -> new double[] {s.lng(), s.lat()})
              .toList();
      out.addAllShape(routeFallback.apply(coords));
    }
    return out.build();
  }
}
