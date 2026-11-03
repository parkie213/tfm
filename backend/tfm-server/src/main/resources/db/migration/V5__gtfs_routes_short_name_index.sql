-- Live-bus trip matching joins incoming journeys to routes by route_short_name (the line label).
-- The single-journey path did one lookup; the batched matcher (matchTripIds) hash-joins a whole
-- poll's journeys against gtfs_routes, so an index on route_short_name avoids a seq scan per poll.
CREATE INDEX IF NOT EXISTS gtfs_routes_short_name ON gtfs_routes (route_short_name);
