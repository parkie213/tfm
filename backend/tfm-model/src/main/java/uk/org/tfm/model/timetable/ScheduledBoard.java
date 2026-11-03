package uk.org.tfm.model.timetable;

import java.util.List;

/** Timetable-only departures for a stop; generatedAt is an ISO timestamp. */
public record ScheduledBoard(
    String stopName, String generatedAt, List<ScheduledDeparture> departures) {
  public ScheduledBoard {
    departures = List.copyOf(departures);
  }
}
