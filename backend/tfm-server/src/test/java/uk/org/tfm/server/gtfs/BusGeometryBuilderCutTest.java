package uk.org.tfm.server.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.org.tfm.proto.v1.LatLng;
import uk.org.tfm.server.db.OsrmRouter.SnappedPath;

/**
 * Unit tests for {@link BusGeometryBuilder#cutOutAndBack} — the round-trip → single-direction cut.
 */
class BusGeometryBuilderCutTest {

  /** Build an aligned snapped path from parallel (lng, lat) arrays; node ids = 1..n. */
  private static SnappedPath path(double[] lng, double[] lat) {
    List<LatLng> g = new ArrayList<>(lng.length);
    long[] nodes = new long[lng.length];
    for (int i = 0; i < lng.length; i++) {
      g.add(LatLng.newBuilder().setLat(lat[i]).setLng(lng[i]).build());
      nodes[i] = i + 1;
    }
    return new SnappedPath(List.copyOf(g), nodes);
  }

  @Test
  void outAndBackKeepsOnlyTheOutboundLeg() {
    // East to lng 0.02 and straight back to the start (a classic round trip), all at lat 52.
    SnappedPath sp =
        path(
            new double[] {0.0, 0.01, 0.02, 0.01, 0.0}, new double[] {52.0, 52.0, 52.0, 52.0, 52.0});

    SnappedPath cut = BusGeometryBuilder.cutOutAndBack(sp);

    assertEquals(3, cut.geometry().size(), "should keep vertices 0..apex (the farthest point)");
    assertEquals(3, cut.nodes().length, "geometry and node ids stay aligned");
    assertEquals(0.02, cut.geometry().get(2).getLng(), 1e-9, "last kept vertex is the apex");
  }

  @Test
  void straightOneWayLineIsUnchanged() {
    // Its end IS its farthest point, so it never matches the out-and-back signature.
    SnappedPath sp =
        path(
            new double[] {0.0, 0.01, 0.02, 0.03, 0.04},
            new double[] {52.0, 52.0, 52.0, 52.0, 52.0});

    assertSame(
        sp,
        BusGeometryBuilder.cutOutAndBack(sp),
        "a clean one-way line must be returned untouched");
  }

  @Test
  void lShapedOneWayLineIsUnchanged() {
    // Goes east then north — end is far from start, so not a round trip.
    SnappedPath sp =
        path(
            new double[] {0.0, 0.01, 0.02, 0.02, 0.02},
            new double[] {52.0, 52.0, 52.0, 52.01, 52.02});

    assertSame(
        sp,
        BusGeometryBuilder.cutOutAndBack(sp),
        "an L-shaped one-way line must be returned untouched");
  }

  @Test
  void tooShortToCut() {
    SnappedPath sp = path(new double[] {0.0, 0.01, 0.0}, new double[] {52.0, 52.0, 52.0});
    assertSame(
        sp, BusGeometryBuilder.cutOutAndBack(sp), "paths with < 4 vertices are returned untouched");
  }
}
