package uk.org.tfm.model.timetable;

import java.util.List;

/** Journey labels, ordered calling points and supplied route shape. Unknown journeys are empty. */
public record JourneyDetails(
    String line,
    String destination,
    String operator,
    List<CallingPoint> stops,
    List<RoutePoint> shape) {
  public static final JourneyDetails EMPTY = new JourneyDetails("", "", "", List.of(), List.of());

  public JourneyDetails {
    stops = List.copyOf(stops);
    shape = List.copyOf(shape);
  }
}
