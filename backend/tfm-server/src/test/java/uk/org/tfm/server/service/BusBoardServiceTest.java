package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.storage.TimetableStore;
import uk.org.tfm.model.timetable.*;
import uk.org.tfm.proto.v1.GetBusBoardRequest;

class BusBoardServiceTest {
  private static final class Store implements TimetableStore {
    final Map<String, ScheduledBoard> boards = new HashMap<>();
    final Set<String> failures = new HashSet<>();
    final List<String> calls = new ArrayList<>();
    String requestedDate;
    int requestedRows;

    public ScheduledBoard getDepartures(String stop, int rows, int lookahead, String date)
        throws StorageException {
      calls.add(stop);
      requestedDate = date;
      requestedRows = rows;
      if (failures.contains(stop)) throw new StorageException("query failed", null);
      return boards.getOrDefault(stop, new ScheduledBoard(stop, "", List.of()));
    }

    public JourneyDetails getServiceStops(String id) {
      throw new UnsupportedOperationException();
    }

    public JourneyDetails getRouteStops(String name, String longName, String operator) {
      throw new UnsupportedOperationException();
    }

    public Map<JourneyKey, String> matchTripIds(Collection<JourneyKey> keys) {
      throw new UnsupportedOperationException();
    }

    public boolean hasTimetable() {
      return true;
    }

    public long tripCount() {
      return 0;
    }

    public void bulkLoad(TimetableSource source) {
      throw new UnsupportedOperationException();
    }
  }

  private static ScheduledDeparture departure(String time, String trip) {
    return new ScheduledDeparture(time, "5", "Leicester", "Bus Co", trip);
  }

  private static void board(Store store, String stop, ScheduledDeparture... rows) {
    store.boards.put(stop, new ScheduledBoard("Stop " + stop, "", List.of(rows)));
  }

  private static BusBoardService service(Store store) {
    return new BusBoardService(new BusTimetableService(store, coords -> List.of()));
  }

  private static GetBusBoardRequest request(String... stops) {
    return GetBusBoardRequest.newBuilder().addAllAtcoCodes(List.of(stops)).build();
  }

  @Test
  void mergesSelectedStopsAndKeepsTheEarliestCallWithItsBoardingStop() throws Exception {
    var store = new Store();
    board(store, "a", departure("10:10", "shared"), departure("10:30", "later"));
    board(store, "b", departure("10:05", "shared"), departure("10:20", "other"));
    var result = service(store).getBoard(request("a", "b"));
    assertEquals(
        List.of("shared", "other", "later"),
        result.getDeparturesList().stream().map(r -> r.getDeparture().getServiceId()).toList());
    assertEquals("b", result.getDepartures(0).getAtcoCode());
    assertEquals("Stop b", result.getDepartures(0).getStopName());
    assertEquals("10:05", result.getDepartures(0).getDeparture().getScheduledTime());
    assertFalse(result.getTruncated());
  }

  @Test
  void equalCallTimesUseExplicitSelectionOrderAndRepeatedStopCodesAreQueriedOnce()
      throws Exception {
    var store = new Store();
    board(store, "a", departure("10:10", "shared"));
    board(store, "b", departure("10:10", "shared"));
    var result = service(store).getBoard(request(" b ", "a", "b"));
    assertEquals(List.of("b", "a"), store.calls);
    assertEquals("b", result.getDepartures(0).getAtcoCode());
  }

  @Test
  void appliesTheLimitAfterDeduplicationAndReportsTruncation() throws Exception {
    var store = new Store();
    board(
        store,
        "a",
        departure("10:10", "shared"),
        departure("10:11", "shared"),
        departure("10:30", "unique"));
    board(store, "b", departure("10:12", "shared"), departure("10:20", "other"));
    var result = service(store).getBoard(request("a", "b").toBuilder().setNumRows(2).build());
    assertEquals(2, result.getDeparturesCount());
    assertEquals("other", result.getDepartures(1).getDeparture().getServiceId());
    assertTrue(result.getTruncated());
    assertEquals(Integer.MAX_VALUE, store.requestedRows);
  }

  @Test
  void unidentifiedTripsAreNotDeduplicatedByMatchingLabelsOrTimes() throws Exception {
    var store = new Store();
    board(store, "a", departure("10:10", ""), departure("10:10", ""));
    board(store, "b", departure("10:10", ""));
    assertEquals(3, service(store).getBoard(request("a", "b")).getDeparturesCount());
  }

  @Test
  void partialFailureIsExplicitAndAllStopFailureDoesNotLookLikeAnEmptyBoard() throws Exception {
    var store = new Store();
    board(store, "a", departure("10:10", "trip"));
    store.failures.add("b");
    var result = service(store).getBoard(request("a", "b"));
    assertEquals(List.of("b"), result.getUnavailableAtcoCodesList());
    assertEquals(1, result.getDeparturesCount());
    store.failures.add("a");
    assertThrows(StorageException.class, () -> service(store).getBoard(request("a", "b")));
  }

  @Test
  void wholeDayUsesTheRequestedCalendarDateWithoutTheUpcomingDefaultLimit() throws Exception {
    var store = new Store();
    var rows = new ArrayList<ScheduledDeparture>();
    for (int i = 0; i < 20; i++) rows.add(departure("10:10", "trip-" + i));
    store.boards.put("a", new ScheduledBoard("Stop a", "", rows));
    var result = service(store).getBoard(request("a").toBuilder().setDate("2026-11-04").build());
    assertEquals(20, result.getDeparturesCount());
    assertFalse(result.getTruncated());
    assertEquals("2026-11-04", store.requestedDate);
    assertEquals(0, store.requestedRows);
    assertEquals(14, service(store).getBoard(request("a")).getDeparturesCount());
  }

  @Test
  void anEmptySuccessfulStopIsNotReportedAsUnavailable() throws Exception {
    var result = service(new Store()).getBoard(request("a"));
    assertEquals(0, result.getDeparturesCount());
    assertEquals(0, result.getUnavailableAtcoCodesCount());
  }

  @Test
  void rejectsEmptyOversizedOrBlankSelectionsAndInvalidDatesOrLimits() {
    assertThrows(IllegalArgumentException.class, () -> BusBoardService.normalize(request()));
    assertThrows(IllegalArgumentException.class, () -> BusBoardService.normalize(request(" ")));
    var oversized = GetBusBoardRequest.newBuilder();
    for (int i = 0; i < 51; i++) oversized.addAtcoCodes("stop-" + i);
    assertThrows(
        IllegalArgumentException.class, () -> BusBoardService.normalize(oversized.build()));
    for (String date : List.of("tomorrow", "2026-02-30"))
      assertThrows(
          IllegalArgumentException.class,
          () -> BusBoardService.normalize(request("a").toBuilder().setDate(date).build()));
    for (int limit : List.of(-1, 101))
      assertThrows(
          IllegalArgumentException.class,
          () -> BusBoardService.normalize(request("a").toBuilder().setNumRows(limit).build()));
  }
}
