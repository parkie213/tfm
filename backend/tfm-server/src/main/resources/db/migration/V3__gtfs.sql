-- GTFS timetable (England, region-configurable). Refreshed by GtfsBulkLoader via TRUNCATE + COPY.
-- No foreign keys (bulk-load speed; BODS data has occasional orphans). departure_secs is INTEGER
-- seconds since service-day midnight and MAY exceed 86400 (after-midnight services) — never cast to TIME.
--
-- NOTE: at the regional default (East Midlands ~3M stop_times) these indexes are fine to maintain on
-- COPY. For an all-England load (~150M rows) prefer drop-index → COPY → recreate, or a versioned swap.

CREATE TABLE gtfs_agency (
    agency_id   TEXT NOT NULL,
    agency_name TEXT NOT NULL
);

CREATE TABLE gtfs_routes (
    route_id         TEXT NOT NULL,
    agency_id        TEXT NOT NULL DEFAULT '',
    route_short_name TEXT NOT NULL DEFAULT '',
    route_long_name  TEXT NOT NULL DEFAULT ''
);

CREATE TABLE gtfs_trips (
    trip_id       TEXT NOT NULL,
    route_id      TEXT NOT NULL,
    service_id    TEXT NOT NULL,
    trip_headsign TEXT NOT NULL DEFAULT ''
);

CREATE TABLE gtfs_stop_times (
    trip_id        TEXT NOT NULL,
    stop_id        TEXT NOT NULL,          -- == NaPTAN ATCO code
    stop_sequence  INTEGER NOT NULL,
    departure_secs INTEGER NOT NULL        -- seconds since service-day midnight; may exceed 86400
);

CREATE TABLE gtfs_calendar (
    service_id TEXT NOT NULL,
    monday     BOOLEAN NOT NULL,
    tuesday    BOOLEAN NOT NULL,
    wednesday  BOOLEAN NOT NULL,
    thursday   BOOLEAN NOT NULL,
    friday     BOOLEAN NOT NULL,
    saturday   BOOLEAN NOT NULL,
    sunday     BOOLEAN NOT NULL,
    start_date DATE NOT NULL,
    end_date   DATE NOT NULL
);

CREATE TABLE gtfs_calendar_dates (
    service_id     TEXT NOT NULL,
    exception_date DATE NOT NULL,
    exception_type SMALLINT NOT NULL  -- 1 = added, 2 = removed
);

-- The critical index: next-departures-at-stop. Equality (stop_id) then range (departure_secs).
CREATE INDEX gtfs_stop_times_stop_dep ON gtfs_stop_times (stop_id, departure_secs);
CREATE INDEX gtfs_stop_times_trip     ON gtfs_stop_times (trip_id);
CREATE INDEX gtfs_trips_trip          ON gtfs_trips (trip_id);
CREATE INDEX gtfs_trips_service       ON gtfs_trips (service_id);
CREATE INDEX gtfs_routes_route        ON gtfs_routes (route_id);
CREATE INDEX gtfs_agency_id           ON gtfs_agency (agency_id);
CREATE INDEX gtfs_calendar_service    ON gtfs_calendar (service_id);
CREATE INDEX gtfs_calendar_dates_svc  ON gtfs_calendar_dates (service_id, exception_date);
