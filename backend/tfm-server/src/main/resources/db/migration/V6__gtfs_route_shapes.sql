-- Derived, simplified route geometry for the "all routes" map overlay.
--
-- gtfs_shapes is ~4.9M raw points with only a (shape_id, seq) btree, so a per-pan
-- spatial scan over it is unworkable. Instead the GTFS loader rebuilds this table once
-- per load: one LineString per distinct shape_id, simplified, tagged with its route's
-- short/long name, and GiST-indexed so a viewport query is an index hit (mirrors the
-- `stops` table's spatial pattern). Starts empty; GtfsBulkLoader.buildRouteShapes()
-- populates it after each successful load.
CREATE TABLE gtfs_route_shapes (
    shape_id         TEXT NOT NULL PRIMARY KEY,
    route_short_name TEXT NOT NULL DEFAULT '',
    route_long_name  TEXT NOT NULL DEFAULT '',
    geom             geometry(LineString, 4326) NOT NULL
);

CREATE INDEX gtfs_route_shapes_geom ON gtfs_route_shapes USING GIST (geom);
