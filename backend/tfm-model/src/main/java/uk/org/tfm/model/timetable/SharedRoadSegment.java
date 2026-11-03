package uk.org.tfm.model.timetable;

import java.util.List;

/** Bus map geometry data; points are ordered WGS84 latitude/longitude. */
public record SharedRoadSegment(List<RoutePoint> points, List<BusRouteLabel> routes) {
  public SharedRoadSegment {
    points = List.copyOf(points);
    routes = List.copyOf(routes);
  }
}
