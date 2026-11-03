package uk.org.tfm.server.gtfs.geometry;

import java.util.List;
import uk.org.tfm.proto.v1.LatLng;

/**
 * A shared "trunk": a maximal chain of OSM edges traversed by the SAME set of routes. The geometry
 * is rebuilt from node coordinates (exact, since a node has one coordinate across every route), and
 * {@code routeShortNames} is the consistently ordered route-set carried by every edge of the chain
 * — so the caller can draw one centreline and highlight it for any selected member route.
 *
 * <p>Carries only short names; the caller decorates them with long names + operators (both held in
 * loader/DB state) before persisting.
 */
public record Trunk(List<LatLng> geom, List<String> routeShortNames) {}
