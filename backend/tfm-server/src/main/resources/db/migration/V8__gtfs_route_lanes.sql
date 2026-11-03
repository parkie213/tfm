-- Bundled route geometry for the "tube-map" parallel-lane overlay.
--
-- Each row is one route's full, node-aligned road geometry (snapped via OSRM with OSM node
-- annotations) plus, per vertex, which lane it occupies on that road segment and how many routes
-- share it. The lane assignment is GLOBAL (it depends on every route sharing a segment), so the whole
-- table is rebuilt once per GTFS load by GtfsBulkLoader.buildBundledRoutes(). The client offsets each
-- vertex perpendicular by (lane - (lane_count-1)/2) * spacing_px, so overlapping routes fan into
-- ordered parallel lanes instead of stacking.
--
-- `lanes` and `lane_counts` are parallel to the geometry's points (same order, same length): geometry
-- is NOT simplified here, because simplification would drop vertices and break that alignment.
CREATE TABLE gtfs_route_lanes (
    shape_id         TEXT NOT NULL PRIMARY KEY,           -- osrm:<stop-pattern md5>
    route_short_name TEXT NOT NULL DEFAULT '',
    route_long_name  TEXT NOT NULL DEFAULT '',
    geom             geometry(LineString, 4326) NOT NULL,
    lanes            INTEGER[] NOT NULL,                  -- per-vertex lane rank (0-based)
    lane_counts      INTEGER[] NOT NULL                   -- per-vertex total lanes on that segment
);

CREATE INDEX gtfs_route_lanes_geom ON gtfs_route_lanes USING GIST (geom);
