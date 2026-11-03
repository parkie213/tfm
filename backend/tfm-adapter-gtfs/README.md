# tfm-adapter-gtfs

The adapter for BODS regional GTFS bus timetables. Production dependencies are `tfm-model` and
SLF4J; HTTP, ZIP reading, hashing, and CSV parsing use the JDK. This module has no server, database,
protobuf, or Dagger dependency.

`GtfsClient` implements `TimetableProvider`. The server supplies `GTFS_CACHE_DIR`, `GTFS_REGIONS`,
and `GTFS_LOAD_SHAPES` through its constructor. Empty region configuration defaults to
`east_midlands`. Archives are cached for 24 hours and downloaded through a temporary file and
atomic move, with a 30-second connection timeout and a 15-minute request timeout.

Fetching prepares a `TimetableSnapshot`: its version is the SHA-256 of the configured archives'
bytes in region order, preserving the existing database freshness key. Reading the snapshot decodes
and combines the archives. This separation lets the server skip parsing unchanged feeds as well as
skip their database imports. Snapshots refer to the adapter's cached archives and should be read in
the same refresh cycle, before another refresh replaces those archives.

`GtfsParser` and `GtfsLoader` are internal decoding helpers. They produce typed model records for:

- Agencies, routes (including optional supplied colors), and journeys.
- Calling-point departure times, in service-day seconds including after midnight.
- Regular operating calendars and date exceptions, with ISO local dates.
- Optional supplied route-shape points in WGS84 coordinates.

Missing required tables/headers fail decoding; optional calendars, shape data, colors, and shape IDs
remain optional. Disabling shape loading retains journey shape identifiers while omitting points.
Download/cache errors become `FeedException.Kind.UNAVAILABLE`; archive decoding failures become
`INVALID_RESPONSE`. The adapter does not generate route geometry or query departures.

The server retains scheduling, enabled/disabled policy, database fingerprint checks, transactional
imports, departure queries, and derived geometry/OSRM work.

Build and run tests from `backend/` with JDK 25 and Maven:

```sh
mvn -pl tfm-adapter-gtfs -am verify
```

Tests create small cached archives: no live feed, credentials, or database are needed. They cover
multi-region fingerprints, deferred decoding, after-midnight times, calendars, optional fields,
shape loading, and malformed data. See [the backend module guide](../README.md).
