package uk.org.tfm.server.bods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.org.tfm.proto.v1.LatLng;
import uk.org.tfm.server.bods.RouteSnapper.Route;
import uk.org.tfm.server.bods.RouteSnapper.Snap;

/**
 * Unit tests for road-geometry snapping. Uses straight east-west routes at lat 52.0 so chainage is
 * a known multiple of the segment length; one out-and-back route checks the prior-chainage window
 * picks the correct lobe at a self-overlap.
 */
class RouteSnapperTest {

  /** metres per degree of longitude at lat 52.0 — segment length of a 0.010° east step. */
  private static final double M_PER_DEG_LNG_52 = 111_320 * Math.cos(52.0 * Math.PI / 180);

  private static final double STEP_M = 0.010 * M_PER_DEG_LNG_52; // ≈ 685 m

  private static Route route(double... lngs) {
    List<LatLng> shape = new ArrayList<>();
    for (double lng : lngs) {
      shape.add(LatLng.newBuilder().setLat(52.0).setLng(lng).build());
    }
    return new Route(shape);
  }

  @Test
  void snapsOntoLineAndReportsChainageDistanceBearing() {
    Route r = route(0.000, 0.010, 0.020);
    // Fix at the midpoint of the first segment, nudged ~11 m north of the line.
    Optional<Snap> snap = RouteSnapper.snap(r, 52.0001, 0.005);
    assertTrue(snap.isPresent());
    Snap s = snap.get();
    assertEquals(52.0, s.lat(), 1e-6); // pulled back onto the line
    assertEquals(0.005, s.lng(), 1e-6);
    assertEquals(STEP_M / 2, s.chainage(), 1.0); // halfway along the first ~685 m segment
    assertEquals(0.0001 * 111_320, s.distMetres(), 0.5); // ~11 m off
    assertEquals(90, s.bearing()); // heading due east
  }

  @Test
  void priorChainageWindowPicksTheNearLobeOnAnOutAndBack() {
    // Out-and-back over the same ground: east 0.000→0.020 then back 0.020→0.000. A fix at lng 0.010
    // is
    // ambiguous — outbound chainage ≈ STEP_M, return chainage ≈ 3*STEP_M.
    Route r = route(0.000, 0.020, 0.000);
    double outbound = STEP_M; // ≈ 685 m
    double inbound = 3 * STEP_M; // ≈ 2056 m

    Snap onOutbound = RouteSnapper.snap(r, 52.0, 0.010, 500).orElseThrow();
    assertEquals(outbound, onOutbound.chainage(), 5.0);

    Snap onReturn = RouteSnapper.snap(r, 52.0, 0.010, 1800).orElseThrow();
    assertEquals(inbound, onReturn.chainage(), 5.0);
  }

  @Test
  void fallsBackToGlobalSearchWhenNothingInWindow() {
    Route r = route(0.000, 0.010, 0.020);
    // priorS far beyond the route end → window empties → global search still finds the nearest
    // point.
    Snap s = RouteSnapper.snap(r, 52.0, 0.005, 9_999).orElseThrow();
    assertEquals(STEP_M / 2, s.chainage(), 1.0);
  }

  @Test
  void emptyOrDegenerateRouteReturnsEmpty() {
    assertTrue(RouteSnapper.snap(route(0.000), 52.0, 0.000).isEmpty()); // single point, no segment
    assertTrue(RouteSnapper.snap(new Route(List.of()), 52.0, 0.000).isEmpty());
  }
}
