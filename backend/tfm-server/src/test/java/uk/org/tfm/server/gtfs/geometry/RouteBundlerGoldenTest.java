package uk.org.tfm.server.gtfs.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.org.tfm.proto.v1.LatLng;

/** Regression fixture for shared route membership and centreline geometry. */
final class RouteBundlerGoldenTest {

  private static LatLng at(double lat, double lng) {
    return LatLng.newBuilder().setLat(lat).setLng(lng).build();
  }

  // Shared nodes 2 and 3 carry identical coordinates across both routes (a node has one location).
  private static final LatLng C1 = at(52.10, -1.00);
  private static final LatLng C2 = at(52.11, -1.01); // node 2 (shared)
  private static final LatLng C3 = at(52.12, -1.02); // node 3 (shared)
  private static final LatLng C4 = at(52.13, -1.03);
  private static final LatLng C5 = at(52.20, -1.10);
  private static final LatLng C6 = at(52.21, -1.11);

  // route "1": 1 -> 2 -> 3 -> 4 ; route "2": 5 -> 2 -> 3 -> 6 (shares edge 2-3).
  private static final BundledRoute R1 =
      new BundledRoute(
          "s1", "1", "Town Circular", List.of(C1, C2, C3, C4), new long[] {1, 2, 3, 4});
  private static final BundledRoute R2 =
      new BundledRoute(
          "s2", "2", "Hospital Line", List.of(C5, C2, C3, C6), new long[] {5, 2, 3, 6});
  private static final List<BundledRoute> ROUTES = List.of(R1, R2);

  @Test
  void sharedEdgeCarriesBothRoutes() {
    Map<EdgeKey, Set<String>> edgeRoutes = RouteBundler.buildEdgeRoutes(ROUTES);
    assertEquals(
        Set.of("1", "2"), edgeRoutes.get(EdgeKey.of(2, 3)), "shared edge 2-3 carries both");
    assertEquals(Set.of("1"), edgeRoutes.get(EdgeKey.of(1, 2)));
    assertEquals(Set.of("2"), edgeRoutes.get(EdgeKey.of(3, 6)));
    assertEquals(5, edgeRoutes.size(), "five distinct undirected edges");
  }

  @Test
  void trunksCollapseToOneSharedAndFourSingles() {
    Map<EdgeKey, Set<String>> edgeRoutes = RouteBundler.buildEdgeRoutes(ROUTES);
    List<Trunk> trunks = RouteBundler.buildTrunks(ROUTES, edgeRoutes);

    // {1,2} shared (edge 2-3) + {1}×2 (edges 1-2, 3-4) + {2}×2 (edges 5-2, 3-6) = 5 trunks.
    assertEquals(5, trunks.size(), "one shared trunk + four single-route chains");

    Trunk shared =
        trunks.stream()
            .filter(t -> t.routeShortNames().equals(List.of("1", "2")))
            .findFirst()
            .orElse(null);
    assertNotNull(shared, "the shared trunk carries both routes, consistently ordered");
    assertEquals(2, shared.geom().size(), "shared trunk spans the single edge 2-3");
    Set<LatLng> pts = Set.copyOf(shared.geom());
    assertEquals(Set.of(C2, C3), pts, "shared trunk geometry is the shared node coordinates");

    long singletons = trunks.stream().filter(t -> t.routeShortNames().size() == 1).count();
    assertEquals(4, singletons, "four single-route chains");
    assertTrue(trunks.stream().allMatch(t -> t.geom().size() >= 2), "no degenerate trunks");
  }
}
