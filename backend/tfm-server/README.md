# tfm-server

The runnable application: gRPC endpoints, application services, caching, database access,
feed lifecycles, and Dagger wiring. It depends on `tfm-model`, `tfm-adapter-darwin`, `tfm-adapter-bods`,
`tfm-adapter-stops`, `tfm-adapter-gtfs`, `tfm-adapter-darwin-kafka`, `tfm-storage-postgres`, and `tfm-proto`.

The extracted train-board path is wired in `DarwinModule`: a Darwin adapter supplies the model's
`BoardProvider` and `FeedHealth` interfaces. `TrainBoardService` caches the live provider boards without adding local schedules. `BoardProtoMapper` converts model entries into the
frontend protobuf contract. Provider JSON parsing belongs in the adapter.

The live bus path is wired in `BodsModule`: the BODS adapter supplies `VehiclePositionProvider`.
`BusVehiclePoller` owns upstream polling, retry/backoff, and the child-resource lifecycle.
`BusVehicleProcessor` handles timetable matching/cache, two-fix tracks, and asynchronous route-shape
loading and road snapping. `BusVehicleService` publishes immutable snapshots, serves freshness and
viewport-filtered reads, and fans updates out to browser streams. Failed polls retain the last good
snapshot. Shutdown stops polling before closing the processor's shape worker and browser streams.
The frontend RPC contract is unchanged; provider HTTP/SIRI parsing stays in the BODS adapter.

Reference catalogs are wired in `NaptanModule` as `CatalogProvider<StopDefinition>` and
`CatalogProvider<LocalityDefinition>`. The existing loader facades delegate to `CatalogRefresh`,
which imports immediately and then daily. Adapter clients own download/cache/CSV handling; the server
binds `StopStore` and `LocalityStore` in `DbModule`. Those storage implementations own bulk
replacement, coordinate reprojection, spatial/search queries, and counts. The server retains search
result merging, health caching, and protobuf mapping in `CatalogProtoMapper`.

GTFS is wired in `GtfsModule` as `TimetableProvider`. `TimetableRefreshService` asks for a source
revision, checks the existing database fingerprint, and decodes/imports only changed feeds. It owns startup/daily scheduling and delegates map work to
`BusGeometryBuilder`. The builder owns OSRM fallback, shared-road calculations, and geometry freshness/publication.
`BusGeometryStore` delegates route-pattern queries, supplied-shape simplification, snapped-route
persistence, and shared-segment staging to `PostgresBusGeometryStore`. Typed model points and route
labels cross this boundary; JDBC, WKT, and PostGIS stay in storage. The geometry algorithm fingerprint
tracks the builder, PostgreSQL geometry store, router, and pure geometry package so changing map construction triggers a rebuild.
`TimetableStore` owns
transactional imports and timetable queries in `tfm-storage-postgres`; `LoadStateStore` owns freshness
fingerprints and atomic geometry publication. `BusTimetableService` maps stored schedules to protobuf
and applies the existing road-routing fallback when supplied shapes are absent.
The network map uses shared road centrelines with route membership and selection colours.
Per-route offset lanes are retired; migration V23 drops their unused live and staging tables. Archive
paths, HTTP requests, CSV parsing, and source cache policy belong to the adapter.

`FeedModule` binds the Kafka adapter to `TrainEventFeed`, supplying configuration and the application's
location-scope predicate. `DarwinFeedCoordinator` owns the feed lifecycle and dispatches schedule, platform, and movement
events. `DarwinScheduleFeedService` buffers schedules for its independent 60-second persistence
tick, with a final flush at shutdown. `DarwinPlatformService` owns platform/movement state,
station-board enrichment, and platform-inventory maintenance. Kafka polling, reconnects, authentication, raw
JSON decoding, and the sampling CLI live in `tfm-adapter-darwin-kafka`.

Rail persistence is wired through `TrainScheduleStore` and `StationPlatformStore` in `DbModule`;
the PostgreSQL implementations live in `tfm-storage-postgres`. Derived bus-route geometry generation
still lives here while extraction proceeds. Kafka schedules supply train details and headcodes; XML snapshot imports
are retired. Migration V24 removes file-sourced schedules while preserving Kafka records. Connection-pool construction and
the complete Flyway migration history remain here until the rest of storage is extracted.
Bus boards show scheduled times; live bus locations are supplied separately, without inferred delays.

Useful locations:

- `service/`: RPC handlers, board services, and feed-status reporting.
- `dagger/`: construction and dependency bindings.
- `config/Config.java`: environment configuration and defaults.
- `db/` and `src/main/resources/db/migration/`: database queries and versioned Flyway migrations.
- `Main.java`: application startup and shutdown.

Build and test from `backend/` with JDK 25 and Maven:

```sh
mvn -pl tfm-server -am verify
```

The build packages dependencies into `tfm-server/target/tfm-server.jar`. The backend Dockerfile
builds the same Maven reactor and runs that JAR. See [the project README](../../README.md)
for Docker Compose startup, credentials, and local development; see [the backend module guide](../README.md)
for module boundaries.

Application startup and shutdown are owned by `ApplicationLifecycle`. `LifecycleModule` registers
lazy `ManagedService` providers with Dagger map multibindings; integer keys define startup order:
database/pool and Flyway, catalog loaders, bus timetables, bus polling, Darwin feed, board streams,
then gRPC. Loader startup schedules background work; readiness does not wait for initial feed imports.
Shutdown marks gRPC unhealthy and drains/terminates requests before stopping services in reverse
order, with the pool closed last. Startup failure rolls back every attempted service, including the
partly started one, and shutdown failures are logged without skipping remaining cleanup.

Add a lifecycle registration when introducing a new independently managed application service.
Keep child resources under their owner's lifecycle (for example platform state under the Darwin
feed coordinator); do not register them twice. `Main` starts/closes the single lifecycle and waits
for gRPC termination. Individual service accessors are no longer exposed by the component.

`TfmServiceImpl` is the transport boundary: it validates/normalises requests, delegates to application
services, translates errors into gRPC statuses, and attaches streaming observers. Feature logic lives
in `CatalogService` (stop/place ranking and viewport mapping), `TrainJourneyService` (schedule
resolution, destination matching, and calling points), `StationPlatformService` (platform response
assembly), and `BusMapService` (route viewport responses and map freshness). These services compose
existing model/storage capabilities; the protobuf service and frontend RPC contract are unchanged.
