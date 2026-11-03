-- "Trunk" route geometry for the single multi-coloured line overlay.
--
-- A trunk is a maximal chain of OSM edges traversed by the SAME set of bus routes. Because every
-- route on a trunk shares the identical OSM-node geometry, the client draws ONE smoothed centreline
-- and stripes the routes as constant perpendicular offsets — so a bend is one uniform sweep instead
-- of N independently-snapped lines splaying apart. Built once per GTFS load by
-- GtfsBulkLoader.buildBundledRoutes() (step 5), reusing the shared-edge graph it already computes.
--
-- The route arrays are LANE-ORDERED and parallel to each other: index = lane position, array length =
-- lane count. Geometry is NOT simplified (full OSM-node resolution) so the smoothing reads cleanly.
CREATE TABLE gtfs_route_trunks (
    id                BIGSERIAL PRIMARY KEY,
    geom              geometry(LineString, 4326) NOT NULL,
    route_short_names TEXT[] NOT NULL,
    route_long_names  TEXT[] NOT NULL,
    operators         TEXT[] NOT NULL
);

CREATE INDEX gtfs_route_trunks_geom ON gtfs_route_trunks USING GIST (geom);
