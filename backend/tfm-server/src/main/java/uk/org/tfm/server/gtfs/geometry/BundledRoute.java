package uk.org.tfm.server.gtfs.geometry;

import java.util.List;
import uk.org.tfm.proto.v1.LatLng;

/**
 * A route snapped to OSM node ids, ready for graph building: its display names, the node-aligned
 * geometry ({@code geom.get(i)} is the coordinate of {@code nodes[i]}), and the OSM node sequence.
 *
 * <p>The pure input to {@link RouteBundler}. Snapping (OSRM) and the de-direction post-processing
 * that produce a {@code BundledRoute} live in the loader; everything from here on is deterministic
 * graph computation.
 */
public record BundledRoute(
    String shapeId, String shortName, String longName, List<LatLng> geom, long[] nodes) {}
