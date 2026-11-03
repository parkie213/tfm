package uk.org.tfm.server.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.proto.v1.BusBoardDeparture;
import uk.org.tfm.proto.v1.GetBusBoardRequest;
import uk.org.tfm.proto.v1.GetBusBoardResponse;

/**
 * Assembles a timetable board for an explicit selection of stops. The timetable store decides which
 * calls are upcoming or valid on the requested London calendar date. This service joins those
 * results, keeps the earliest selected call per identified trip, then applies the board limit.
 */
@Singleton
public final class BusBoardService {
  private static final Logger log = LoggerFactory.getLogger(BusBoardService.class);
  private final BusTimetableService timetables;

  @Inject
  public BusBoardService(BusTimetableService timetables) {
    this.timetables = timetables;
  }

  /** Validates and normalizes a selection before querying or opening a long-lived subscription. */
  public static GetBusBoardRequest normalize(GetBusBoardRequest request) {
    var stops = new LinkedHashSet<String>();
    for (String raw : request.getAtcoCodesList()) {
      if (raw.isBlank()) throw new IllegalArgumentException("Stop codes must not be blank.");
      stops.add(raw.trim());
    }
    if (stops.isEmpty() || stops.size() > 50)
      throw new IllegalArgumentException("Select between one and fifty stops.");
    if (request.getNumRows() < 0 || request.getNumRows() > 100)
      throw new IllegalArgumentException("num_rows must be between 0 and 100.");
    String date = request.getDate().trim();
    if (!date.isEmpty()) {
      try {
        if (!LocalDate.parse(date).toString().equals(date))
          throw new DateTimeParseException("Non-canonical date", date, 0);
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("date must be a valid yyyy-MM-dd calendar date.");
      }
    }
    return request.toBuilder().clearAtcoCodes().addAllAtcoCodes(stops).setDate(date).build();
  }

  /**
   * Returns a complete or explicitly partial board. Rows without trip IDs are never deduplicated.
   * All-stop query failure throws so callers do not mistake an outage for an empty timetable.
   */
  public GetBusBoardResponse getBoard(GetBusBoardRequest raw) throws StorageException {
    var request = normalize(raw);
    List<BusBoardDeparture> candidates = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    int succeeded = 0;
    StorageException lastFailure = null;
    for (String stop : request.getAtcoCodesList()) {
      try {
        // Fetch the full relevant result before deduplication and the final board cap. A per-stop
        // cap can hide unique trips behind repeated calls of one trip at a looping route's stop.
        var board =
            timetables.getDepartures(
                stop, request.getDate().isEmpty() ? Integer.MAX_VALUE : 0, 0, request.getDate());
        succeeded++;
        for (var departure : board.getDeparturesList()) {
          candidates.add(
              BusBoardDeparture.newBuilder()
                  .setDeparture(departure)
                  .setAtcoCode(stop)
                  .setStopName(board.getStopName())
                  .build());
        }
      } catch (StorageException e) {
        unavailable.add(stop);
        lastFailure = e;
        log.warn("Bus board query unavailable for stop {}.", stop);
      }
    }
    if (succeeded == 0)
      throw new StorageException("No selected stop could be queried.", lastFailure);
    // Stable sorting preserves request stop order when call times tie.
    candidates.sort(Comparator.comparingInt(row -> seconds(row.getDeparture().getScheduledTime())));
    Set<String> seenTrips = new HashSet<>();
    List<BusBoardDeparture> merged = new ArrayList<>();
    for (var row : candidates) {
      String trip = row.getDeparture().getServiceId();
      if (trip.isBlank() || seenTrips.add(trip)) merged.add(row);
    }
    int limit = request.getNumRows();
    if (limit == 0 && request.getDate().isEmpty()) limit = 14;
    boolean truncated = limit > 0 && merged.size() > limit;
    return GetBusBoardResponse.newBuilder()
        .setGeneratedAt(Instant.now().toString())
        .addAllDepartures(truncated ? merged.subList(0, limit) : merged)
        .setTruncated(truncated)
        .addAllUnavailableAtcoCodes(unavailable)
        .build();
  }

  private static int seconds(String time) {
    try {
      return LocalTime.parse(time).toSecondOfDay();
    } catch (DateTimeParseException e) {
      return Integer.MAX_VALUE;
    }
  }
}
