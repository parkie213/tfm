package uk.org.tfm.server.bods;

import java.util.List;
import java.util.Optional;
import uk.org.tfm.proto.v1.LatLng;

/**
 * Projects a live bus's GPS fix onto its route's road geometry so the marker sits on the
 * carriageway instead of where the raw SIRI-VM fix landed (GPS noise, urban canyon). The
 * server-side twin of the client's {@code frontend/src/lib/route.ts snapToPolyline}: identical
 * equirectangular maths (accurate to well under a metre at a town's scale) and the same
 * prior-chainage window that stops a fix near a self-overlap (loop / out-and-back street) snapping
 * to a far lobe.
 */
final class RouteSnapper {

  private static final double M_PER_DEG_LAT = 111_320;

  // When a prior chainage is known, only consider segments within this window of it (mirrors
  // route.ts).
  private static final double WINDOW_BACK_M = 150;
  private static final double WINDOW_FWD_M = 800;

  private RouteSnapper() {}

  private static double mPerDegLng(double lat) {
    return M_PER_DEG_LAT * Math.cos(lat * Math.PI / 180);
  }

  /**
   * The snapped point: its position, how far the raw fix was off the route, the chainage along the
   * route (metres from the start), and the route's tangent bearing there (compass 0-359).
   */
  record Snap(double lat, double lng, double distMetres, double chainage, int bearing) {}

  /**
   * A route's road geometry prepared once for repeated snapping: parallel lat/lng vertices plus the
   * cumulative distance (metres) to each, so chainage windows are a cheap array lookup. Build it
   * when the shape is first fetched, then reuse across every poll for that trip.
   */
  static final class Route {
    private final double[] lat;
    private final double[] lng;
    private final double[] cum;

    Route(List<LatLng> shape) {
      int n = shape.size();
      lat = new double[n];
      lng = new double[n];
      cum = new double[n];
      for (int i = 0; i < n; i++) {
        lat[i] = shape.get(i).getLat();
        lng[i] = shape.get(i).getLng();
        if (i > 0) {
          cum[i] = cum[i - 1] + metresBetween(lat[i - 1], lng[i - 1], lat[i], lng[i]);
        }
      }
    }

    boolean usable() {
      return lat.length >= 2;
    }
  }

  /** Snaps a fix onto the route with no prior position (global nearest). */
  static Optional<Snap> snap(Route route, double qLat, double qLng) {
    return snap(route, qLat, qLng, -1);
  }

  /**
   * Snaps {@code (qLat, qLng)} to the nearest point on {@code route}. When {@code priorS >= 0} the
   * search is restricted to a chainage window around it (and ties are biased toward it); if nothing
   * falls in the window (a genuine large jump) it retries globally — matching the client exactly.
   */
  static Optional<Snap> snap(Route route, double qLat, double qLng, double priorS) {
    if (route == null || !route.usable()) {
      return Optional.empty();
    }
    double mLng = mPerDegLng(qLat);
    double bestCost = Double.POSITIVE_INFINITY;
    double bestDist = 0;
    double bestS = 0;
    double bestLat = 0;
    double bestLng = 0;
    int bestSeg = -1;
    for (int i = 0; i < route.lat.length - 1; i++) {
      if (priorS >= 0
          && (route.cum[i + 1] < priorS - WINDOW_BACK_M || route.cum[i] > priorS + WINDOW_FWD_M)) {
        continue;
      }
      // Local planar frame centred on the query point (so the point itself is the origin).
      double ax = (route.lng[i] - qLng) * mLng;
      double ay = (route.lat[i] - qLat) * M_PER_DEG_LAT;
      double bx = (route.lng[i + 1] - qLng) * mLng;
      double by = (route.lat[i + 1] - qLat) * M_PER_DEG_LAT;
      double dx = bx - ax;
      double dy = by - ay;
      double segLen2 = dx * dx + dy * dy;
      double t = segLen2 > 0 ? -(ax * dx + ay * dy) / segLen2 : 0;
      t = t < 0 ? 0 : t > 1 ? 1 : t;
      double cx = ax + t * dx;
      double cy = ay + t * dy;
      double dist = Math.hypot(cx, cy);
      double s = route.cum[i] + t * (route.cum[i + 1] - route.cum[i]);
      // Tie-break toward the prior chainage (0.02 m of cost per metre of gap — gentle), as
      // route.ts.
      double cost = priorS < 0 ? dist : dist + 0.02 * Math.abs(s - priorS);
      if (cost < bestCost) {
        bestCost = cost;
        bestDist = dist;
        bestS = s;
        bestLat = qLat + cy / M_PER_DEG_LAT;
        bestLng = qLng + cx / mLng;
        bestSeg = i;
      }
    }
    if (bestSeg < 0) {
      return priorS >= 0 ? snap(route, qLat, qLng, -1) : Optional.empty();
    }
    int bearing = tangentBearing(route, bestSeg);
    return Optional.of(new Snap(bestLat, bestLng, bestDist, bestS, bearing));
  }

  /** Compass bearing (0-359) of segment {@code seg}; 0 = north, 90 = east. */
  private static int tangentBearing(Route route, int seg) {
    double lat0 = (route.lat[seg] + route.lat[seg + 1]) / 2;
    double dx = (route.lng[seg + 1] - route.lng[seg]) * mPerDegLng(lat0);
    double dy = (route.lat[seg + 1] - route.lat[seg]) * M_PER_DEG_LAT;
    double deg = Math.atan2(dx, dy) * 180 / Math.PI;
    return (int) Math.round((deg + 360) % 360) % 360;
  }

  private static double metresBetween(double aLat, double aLng, double bLat, double bLng) {
    double lat0 = (aLat + bLat) / 2;
    double dx = (bLng - aLng) * mPerDegLng(lat0);
    double dy = (bLat - aLat) * M_PER_DEG_LAT;
    return Math.hypot(dx, dy);
  }
}
