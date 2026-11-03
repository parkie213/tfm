package uk.org.tfm.server.gtfs.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.org.tfm.proto.v1.LatLng;

/**
 * The shared-road graph algorithm behind the bus network overlay. Given a set of node-snapped
 * {@link BundledRoute}s it computes, with no DB or network access:
 *
 * <ul>
 *   <li>the shared-edge graph (OSM edge → the route names traversing it),
 *   <li>the maximal same-route-set "trunks" ({@link #buildTrunks}).
 * </ul>
 *
 * <p>This package is the unit whose source hash drives the geometry-algorithm version (see the
 * load-state freshness key) and the target of the golden output test — keep it free of I/O so both
 * stay meaningful. The loader owns everything that touches OSRM or Postgres.
 */
public final class RouteBundler {

  private RouteBundler() {}

  /**
   * Stable global ordering of route names on a shared edge: by leading route number, then natural —
   * so route metadata is returned in a consistent display order.
   */
  public static final Comparator<String> GLOBAL_ORDER =
      Comparator.comparingInt(RouteBundler::numericPrefix).thenComparing(Comparator.naturalOrder());

  /**
   * Shared-edge graph: each OSM edge → the distinct route NAMES traversing it. Keyed by short name
   * (not per-variant) so a route's major variants — and same-named route_ids — share one identity
   * on common streets.
   */
  public static Map<EdgeKey, Set<String>> buildEdgeRoutes(List<BundledRoute> routes) {
    Map<EdgeKey, Set<String>> edgeRoutes = new HashMap<>();
    for (BundledRoute r : routes) {
      long[] ns = r.nodes();
      for (int i = 0; i < ns.length - 1; i++) {
        if (ns[i] != ns[i + 1]) {
          edgeRoutes
              .computeIfAbsent(EdgeKey.of(ns[i], ns[i + 1]), k -> new HashSet<>())
              .add(r.shortName());
        }
      }
    }
    return edgeRoutes;
  }

  /**
   * Collapses the per-route lines into shared trunks — maximal chains of OSM edges traversed by the
   * SAME set of routes. Reuses the {@code edgeRoutes} graph from {@link #buildEdgeRoutes}. Each
   * trunk's geometry is rebuilt from node coordinates (a node has one coordinate across every
   * route) and carries the consistently ordered route-set as short names.
   */
  public static List<Trunk> buildTrunks(
      List<BundledRoute> routes, Map<EdgeKey, Set<String>> edgeRoutes) {
    // OSM node -> coordinate. Identical across routes for a given node, so first writer wins.
    Map<Long, LatLng> nodeCoord = new HashMap<>();
    for (BundledRoute r : routes) {
      long[] ns = r.nodes();
      for (int i = 0; i < ns.length; i++) {
        nodeCoord.putIfAbsent(ns[i], r.geom().get(i));
      }
    }

    // Group edges by their exact route-set signature (sorted route names = canonical key).
    Map<List<String>, List<EdgeKey>> bySignature = new HashMap<>();
    for (var e : edgeRoutes.entrySet()) {
      List<String> sig = new ArrayList<>(e.getValue());
      sig.sort(Comparator.naturalOrder());
      bySignature.computeIfAbsent(sig, k -> new ArrayList<>()).add(e.getKey());
    }

    List<Trunk> out = new ArrayList<>();
    for (var grp : bySignature.entrySet()) {
      // Consistently ordered route identity — shared by every chain of this signature.
      List<String> ordered = List.copyOf(sortedByGlobal(grp.getKey()));

      // Adjacency over only this signature's edges; degree-2 nodes are chain interiors.
      Map<Long, List<Long>> adj = new HashMap<>();
      Set<EdgeKey> remaining = new HashSet<>(grp.getValue());
      for (EdgeKey ek : grp.getValue()) {
        adj.computeIfAbsent(ek.a(), x -> new ArrayList<>()).add(ek.b());
        adj.computeIfAbsent(ek.b(), x -> new ArrayList<>()).add(ek.a());
      }

      // Pass A: chains seeded at endpoints/junctions (degree != 2).
      for (Long start : adj.keySet()) {
        if (adj.get(start).size() == 2) {
          continue;
        }
        for (Long nbr : new ArrayList<>(adj.get(start))) {
          if (!remaining.contains(EdgeKey.of(start, nbr))) {
            continue;
          }
          List<LatLng> geom = walkChain(start, nbr, adj, remaining, nodeCoord);
          if (geom.size() >= 2) {
            out.add(new Trunk(geom, ordered));
          }
        }
      }
      // Pass B: leftover pure cycles (every node degree 2 — no endpoint to seed from).
      while (!remaining.isEmpty()) {
        EdgeKey seed = remaining.iterator().next();
        List<LatLng> geom = walkChain(seed.a(), seed.b(), adj, remaining, nodeCoord);
        if (geom.size() >= 2) {
          out.add(new Trunk(geom, ordered));
        } else {
          remaining.remove(seed); // defensive: never spin on an unwalkable seed
        }
      }
    }
    return out;
  }

  private static List<String> sortedByGlobal(List<String> names) {
    List<String> ordered = new ArrayList<>(names);
    ordered.sort(GLOBAL_ORDER);
    return ordered;
  }

  /**
   * Walks a maximal chain from {@code start} via {@code next}, consuming edges from {@code
   * remaining} and following degree-2 interior nodes until it reaches a junction/endpoint, closes a
   * cycle, or hits an already-consumed edge. Returns the ordered node coordinates (empty if the
   * seed edge was already taken).
   */
  private static List<LatLng> walkChain(
      long start,
      long next,
      Map<Long, List<Long>> adj,
      Set<EdgeKey> remaining,
      Map<Long, LatLng> nodeCoord) {
    if (!remaining.remove(EdgeKey.of(start, next))) {
      return List.of();
    }
    List<Long> seq = new ArrayList<>();
    seq.add(start);
    seq.add(next);
    long prev = start;
    long cur = next;
    while (adj.get(cur).size() == 2) {
      List<Long> nb = adj.get(cur);
      long forward = nb.get(0) == prev ? nb.get(1) : nb.get(0);
      if (!remaining.remove(EdgeKey.of(cur, forward))) {
        break; // cycle closed or the next edge was already consumed
      }
      prev = cur;
      cur = forward;
      seq.add(cur);
      if (cur == start) {
        break; // closed loop back to the seed
      }
    }
    List<LatLng> geom = new ArrayList<>(seq.size());
    for (long n : seq) {
      LatLng c = nodeCoord.get(n);
      if (c != null) {
        geom.add(c);
      }
    }
    return geom;
  }

  /** Leading integer of a route number (e.g. "5A" -> 5); a big sentinel when it has no digits. */
  static int numericPrefix(String s) {
    int i = 0;
    while (i < s.length() && Character.isDigit(s.charAt(i))) {
      i++;
    }
    if (i == 0) {
      return 100_000;
    }
    try {
      return Integer.parseInt(s.substring(0, i));
    } catch (NumberFormatException e) {
      return 100_000;
    }
  }
}
