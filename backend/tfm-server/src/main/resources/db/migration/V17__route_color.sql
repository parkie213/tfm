-- Route colour moves from a client-side hash to the backend (single source of truth).
--
-- gtfs_routes carries the official GTFS routes.txt route_color (optional in the spec; '' when the
-- feed omits it). gtfs_route_trunks gains a lane-ordered colour array, parallel to the existing
-- route_short_names / route_long_names / operators arrays, holding the FINAL CSS colour per lane
-- (official "#rrggbb" where present, else the deterministic hash fallback). Both are populated on
-- the next GTFS ingest by GtfsBulkLoader; the defaults keep existing rows valid until then (the DAO
-- derives a fallback colour at read time when the array is empty/stale).

ALTER TABLE gtfs_routes
    ADD COLUMN route_color TEXT NOT NULL DEFAULT '';

ALTER TABLE gtfs_route_trunks
    ADD COLUMN route_colors TEXT[] NOT NULL DEFAULT '{}';
