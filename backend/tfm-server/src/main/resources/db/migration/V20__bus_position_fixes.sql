-- Logged live bus position fixes — a time-series of GPS observations from the BODS SIRI-VM feed.
--
-- Nothing about live vehicles was persisted before this: BusVehicleService polled the feed every
-- ~10s, enriched each fix (resolved trip_id, road-snapped it onto the route → a chainage, estimated
-- delay), broadcast a snapshot, and discarded it. This table keeps that history so we can later
--   (1) derive empirical accel/decel speed profiles per route (smoother bus animation), and
--   (2) sanity-check the OSRM-snapped geometry of shapeless routes against where buses actually drive
--       (the route-19 / Old Dalby mis-snap class of bug).
--
-- Append-mostly and idempotent: the PK (vehicle_ref, recorded_at) makes re-logging the same fix
-- (we poll faster than the upstream RecordedAtTime updates) a no-op via ON CONFLICT DO NOTHING.
-- Retention is a plain time-window DELETE (see BusPositionLoggerService); at the feed's volume
-- (tens–low-hundreds of vehicles, a distinct fix every ~20-30s, 14-day retention → well under ~1M
-- rows) partitioning is not warranted.
CREATE TABLE bus_position_fixes (
    vehicle_ref      TEXT NOT NULL,                  -- operator + ' ' + VehicleRef (the BusVehicleService track key)
    recorded_at      TIMESTAMPTZ NOT NULL,           -- SIRI-VM RecordedAtTime — the distinct-fix key
    line             TEXT NOT NULL DEFAULT '',       -- PublishedLineName, e.g. "19"
    operator         TEXT NOT NULL DEFAULT '',       -- OperatorRef (NOC)
    trip_id          TEXT NOT NULL DEFAULT '',       -- resolved GTFS trip, or '' (NOT NULL so the partial index works)
    location         GEOGRAPHY(POINT, 4326) NOT NULL,-- raw GPS fix (ground truth for the geometry audit)
    bearing          SMALLINT,                       -- 0-359, or NULL if the feed gave none (no -1 sentinel)
    snapped          BOOLEAN NOT NULL DEFAULT false, -- was the fix confidently road-snapped to its trip?
    snapped_chainage DOUBLE PRECISION,               -- metres along the trip route at the snap; NULL if unsnapped
    delay_secs       INTEGER,                        -- seconds late(+)/early(-); NULL if no progress estimate
    next_stop_seq    INTEGER,                        -- stop_sequence of the upcoming calling point; NULL if none
    PRIMARY KEY (vehicle_ref, recorded_at)           -- idempotent dedup across overlapping ~10s polls
);

-- Phase-2 audit: fetch a route's fixes (joined in by trip_id) over a recent window.
CREATE INDEX bus_fixes_trip_time ON bus_position_fixes (trip_id, recorded_at) WHERE trip_id <> '';
-- Retention: DELETE WHERE recorded_at < cutoff.
CREATE INDEX bus_fixes_recorded_at ON bus_position_fixes (recorded_at);
-- "Latest fix per vehicle" is served by a backward scan of the PK — no extra index needed.
-- No GiST on `location` yet: the only reader (the audit) joins by trip_id and measures against the
-- *lane* geometry's GiST; a spatial index here would just add per-INSERT cost with no reader. Add it
-- when a spatial query over the fixes themselves actually needs it.

-- Delete-heavy churn against this table: vacuum sooner than the 20% default to keep index bloat low.
ALTER TABLE bus_position_fixes
    SET (autovacuum_vacuum_scale_factor = 0.05, autovacuum_vacuum_cost_delay = 2);
