package uk.org.tfm.model.timetable;

import java.util.List;

/** Bus map geometry data; points are ordered WGS84 latitude/longitude. */
public record BusRouteShape(
    String shapeId, String shortName, String longName, List<RoutePoint> points) {
  public BusRouteShape {
    points = List.copyOf(points);
  }
}
