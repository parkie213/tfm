# tfm-model

The provider-independent model and capability interfaces used by the application.
This module depends only on the JDK; it has no protobuf, HTTP, database, or dependency-injection dependencies.

Packages keep each capability's records and provider interfaces together:

| Package under `uk.org.tfm.model` | Types |
| --- | --- |
| `board` | `Board`, `BoardEntry`, `Coach`, `BoardProvider` |
| `vehicle` | `VehiclePosition`, `VehiclePositionProvider` |
| `catalog` | Import definitions, query `Stop`/`Locality` records, ranked hits, `StopPage`, `CatalogProvider<T>` |
| `timetable` | `Timetable`, schedule and query records, `TimetableProvider`, `TimetableSnapshot` |
| `rail` | Train schedules/calling points, platform/movement events, `TrainEventFeed`, `TrainEventSink` |
| `storage` | `StopStore`, `LocalityStore`, `TimetableStore`, `BusGeometryStore`, `LoadStateStore`, `TrainScheduleStore`, `StationPlatformStore`, `LoadState`, count views, `StorageException` |
| `feed` | `FeedException`, `FeedHealth` |

`board` describes station boards and reported predictions; `vehicle` describes live observations;
`catalog` describes reference stops and places. `storage` defines persistence capabilities;
`feed` contains shared feed failure and health contracts.
The catalog, vehicle, and timetable provider interfaces use `FeedException`; other capability types remain
independent of one another. These are packages within one Maven module.

Times are station-local `HH:mm` strings. Generated timestamps retain the source's ISO representation.
Identifiers are opaque and provider-scoped. An absent prediction or delay means unknown;
unknown coach loading is distinct from a reported zero. Status can be known without a predicted time.

Adapters implement the interfaces and return these records. The server translates them into its
frontend API contract. Add model types as real features are migrated; the model currently covers
boards, vehicle observations, reference catalogs, supplied timetables, and train events.

Vehicle observations preserve the existing unknown-value conventions: bearing and origin-departure
seconds are `-1` when absent, observation time is `Instant.EPOCH` when unknown, and optional journey
labels/date are empty strings. Origin-departure seconds and service dates use the service's local time.
The server decides whether a fix is fresh and whether it matches a timetabled journey.

Catalog stops use WGS84 latitude/longitude. Localities retain British National Grid (EPSG:27700)
easting/northing; storage converts them to WGS84. These coordinate systems are explicit in the record
contracts. Catalog timestamps describe the cached source, not the last successful database import.

`Timetable` carries agencies, routes, journeys, stop times, operating calendars/exceptions, and
supplied shape points. Stop times are service-day seconds and may exceed 86400; calendar dates are
ISO local dates. `TimetableSnapshot` exposes a revision before decoding, so an application can skip
parsing and importing an unchanged feed. It exposes no archive path or database representation.

`ScheduledBoard` and `JourneyDetails` describe stored bus departures, calling points, and supplied
route points. `JourneyKey` identifies observations for timetable matching. `TimetableStore` accepts
whole timetables and returns these records; `LoadStateStore` tracks feed and geometry revisions.

Catalog stores accept import definitions and return query records in WGS84. `RankedStop` and
`RankedPlace` retain comparable tier/rank/score metadata so the application can merge search results.
`StopPage` reports viewport truncation. Storage ports throw `StorageException`, without exposing JDBC
or PostgreSQL types; count views let health reporting read totals without needing the full store API.

`TrainScheduleStore` queries and persists train schedules and their existing `TrainCallingPoint`
records. `StationPlatformStore` stores observed labels by TIPLOC; neither exposes driver types.
Schedule reads return empty on storage failure to preserve degraded journey/platform displays;
write failures use `StorageException` so the application can retry.

`TrainEventFeed` delivers platform, movement, and schedule updates through `TrainEventSink`.
The interface owns no Kafka types or provider configuration. Feed callbacks are serialized for a
single feed; the server owns indexing, field merging, persistence, and conversion to frontend data.
Train schedules/calling points describe Kafka schedule records; source identifiers remain opaque,
with existing RID/TIPLOC terminology preserved.

Build from `backend/` with JDK 25 and Maven:

```sh
mvn -pl tfm-model -am verify
```

See [the backend module guide](../README.md) for the dependency layout.

`BusGeometryStore` exposes map persistence using `BusRoutePattern`, `BusRouteShape`,
`SharedRoadSegment`, and `BusRouteLabel`. Geometry uses ordered WGS84 `RoutePoint` values;
SQL/WKT representations stay inside storage. The server retains OSRM routing, shared-edge grouping,
route colours, throttling, and rebuild policy.
