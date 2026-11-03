package uk.org.tfm.model.timetable;

import java.util.List;

/** Bus map geometry data; points are ordered WGS84 latitude/longitude. */
public record BusRoutePattern(
    String shortName,
    String longName,
    String pattern,
    List<RoutePoint> stops,
    List<RoutePoint> shape) {
  public BusRoutePattern {
    stops = List.copyOf(stops);
    shape = List.copyOf(shape);
  }
}
