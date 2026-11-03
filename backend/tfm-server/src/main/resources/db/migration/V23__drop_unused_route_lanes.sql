-- The map uses shared route centrelines; per-route offset lanes are no longer used.
DROP TABLE IF EXISTS gtfs_route_lanes_staging;
DROP TABLE gtfs_route_lanes;
