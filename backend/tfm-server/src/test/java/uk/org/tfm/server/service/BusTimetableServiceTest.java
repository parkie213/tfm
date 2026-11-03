package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.storage.TimetableStore;
import uk.org.tfm.model.timetable.*;
import uk.org.tfm.proto.v1.LatLng;

class BusTimetableServiceTest {
  @Test
  void scheduledBoardDoesNotInventRealtimePredictions() throws Exception {
    var store = new Store();
    store.board =
        new ScheduledBoard(
            "Market Place",
            "2026-10-28T10:00:00Z",
            List.of(new ScheduledDeparture("10:15", "19", "Oakham", "Bus Co", "trip-1")));
    var service =
        new BusTimetableService(
            store,
            coords -> {
              throw new AssertionError("No routing for boards");
            });
    var board = service.getDepartures("stop-1", 10, 0, "");
    assertEquals("Market Place", board.getStopName());
    assertFalse(board.getIsRealtime());
    var row = board.getDepartures(0);
    assertEquals("10:15", row.getScheduledTime());
    assertEquals("19", row.getLine());
    assertEquals("trip-1", row.getServiceId());
    assertEquals("Scheduled", row.getStatus());
    assertEquals("", row.getExpectedTime());
    assertFalse(row.getIsLive());
  }

  @Test
  void suppliedShapeBypassesRouting() throws Exception {
    var store = new Store();
    store.journey =
        new JourneyDetails(
            "19", "Oakham", "Bus Co", List.of(), List.of(new RoutePoint(52.77, -0.88)));
    var service =
        new BusTimetableService(
            store,
            coords -> {
              throw new AssertionError("Supplied shape must win");
            });
    var details = service.getServiceStops("trip-1");
    assertEquals("19", details.getLine());
    assertEquals(52.77, details.getShape(0).getLat());
    assertEquals(-0.88, details.getShape(0).getLng());
  }

  @Test
  void missingShapeRoutesOnlyKnownCoordinatesInLongitudeLatitudeOrder() throws Exception {
    var store = new Store();
    store.journey =
        new JourneyDetails(
            "19",
            "Oakham",
            "Bus Co",
            List.of(
                new CallingPoint(1, "known", "Market", "Melton", "10:00", 52.77, -0.88),
                new CallingPoint(2, "unknown", "Unknown", "", "10:05", 0, 0)),
            List.of());
    var routed = new AtomicReference<List<double[]>>();
    var service =
        new BusTimetableService(
            store,
            coords -> {
              routed.set(coords);
              return List.of(LatLng.newBuilder().setLat(52.78).setLng(-0.87).build());
            });
    var details = service.getRouteStops("19", "", "Bus Co");
    assertEquals(2, details.getStopsCount());
    assertEquals("Unknown", details.getStops(1).getName());
    assertEquals(1, routed.get().size());
    assertArrayEquals(new double[] {-0.88, 52.77}, routed.get().getFirst());
    assertEquals(52.78, details.getShape(0).getLat());
  }

  private static final class Store implements TimetableStore {
    ScheduledBoard board;
    JourneyDetails journey;

    public ScheduledBoard getDepartures(String id, int rows, int lookahead, String date) {
      return board;
    }

    public JourneyDetails getServiceStops(String id) {
      return journey;
    }

    public JourneyDetails getRouteStops(String name, String longName, String operator) {
      return journey;
    }

    public Map<JourneyKey, String> matchTripIds(Collection<JourneyKey> keys) {
      throw new UnsupportedOperationException();
    }

    public boolean hasTimetable() {
      throw new UnsupportedOperationException();
    }

    public long tripCount() {
      throw new UnsupportedOperationException();
    }

    public void bulkLoad(TimetableSource source) {
      throw new UnsupportedOperationException();
    }
  }
}
