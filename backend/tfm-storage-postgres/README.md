# tfm-storage-postgres

PostgreSQL/PostGIS implementations of model storage interfaces. Production dependencies are
`tfm-model`, the PostgreSQL JDBC driver, and SLF4J. This module has no server, protobuf, or Dagger dependency.

Implemented stores:

- `PostgresStopStore` implements `StopStore`: active-stop viewport queries, trigram search,
  atomic bulk replacement through staging/COPY, and stop counts.
- `PostgresLocalityStore` implements `LocalityStore`: trigram place search, bulk replacement,
  British National Grid to WGS84 reprojection, and locality counts.

- `PostgresTimetableStore` implements `TimetableStore`: scheduled boards, calling points, supplied
  shapes, calendar-valid trip matching, counts, and transactional timetable replacement through COPY.
  Package-private `TimetableDepartureQueries` owns departure/calendar queries,
  `TimetableJourneyQueries` owns calling points/shapes/representative route lookup, and
  `TimetableTripMatcher` owns batched live-journey matching. The public store retains the model
  boundary and error translation; `PostgresTimetableImporter` owns replacement imports.
- `PostgresLoadStateStore` implements `LoadStateStore`: feed fingerprints and atomic publication
  of staged bus geometry together with its fingerprint.

- `PostgresTrainScheduleStore` implements `TrainScheduleStore`: schedule/headcode lookup, calling
  points, journey matching, transactional Kafka schedule replacement, and age-based eviction.
- `PostgresStationPlatformStore` implements `StationPlatformStore`: platform inventory reads and
  transactional observation batches.

- `PostgresBusGeometryStore` implements `BusGeometryStore`: representative route-pattern queries,
  supplied-shape simplification, snapped-route reuse/writes, and transactional shared-road staging.
  WKT and SQL arrays are internal; inputs/outputs use WGS84 `RoutePoint` and route-label records.
  Publishing staged segments and their fingerprint remains atomic through `LoadStateStore`.

Construct stores with the application's `DataSource`; the stores do not construct a pool, read
credentials, run migrations, or schedule work. Query results are model records in WGS84, including
ranking metadata and viewport truncation. Import inputs are model catalog definitions. Driver/IO
failures cross the boundary as `StorageException`, with the original cause retained.

Existing behaviour is preserved: 500-stop viewport cap, bounded search limits, local catchment and
Melton proximity bias, rail-first stop ranking, strong-place ranking, and per-transaction trigram
thresholds. The server merges ranked stops and places and converts model records to protobuf.

Rail schedule reads preserve the existing graceful degradation to empty results on SQL failure;
eviction remains best-effort. Schedule and platform writes expose `StorageException` for application
retry handling. No JDBC types cross the model boundary.

Connection-pool setup, the single complete Flyway migration history, and
bus geometry generation remain in `tfm-server` for later extraction. Keep migration versions and
checksums intact; do not copy a subset of the migration history into this module.

Build and test from `backend/` with JDK 25 and Maven:

```sh
mvn -pl tfm-storage-postgres -am verify
```

Tests use JDBC protocol stubs for result mapping, parameter binding, caps, ranking, failure
translation, timetable/calendar mapping, geometry publication, and transaction rollback. They do not execute PostgreSQL SQL or test PostGIS/COPY
against a live database. See [the backend module guide](../README.md).
