# Backend modules

The backend is being separated by responsibility, covering live train boards, bus vehicle feeds, reference catalogs, and bus timetables.

| Module | Responsibility | Dependencies within this project |
| --- | --- | --- |
| [`tfm-model`](tfm-model/README.md) | Plain Java board, vehicle, catalog, and timetable contracts | None |
| [`tfm-adapter-darwin`](tfm-adapter-darwin/README.md) | LDBWS HTTP requests and Darwin JSON decoding | [`tfm-model`](tfm-model/README.md) |
| [`tfm-adapter-darwin-kafka`](tfm-adapter-darwin-kafka/README.md) | Kafka consumption and Darwin push-port JSON decoding | [`tfm-model`](tfm-model/README.md) |
| [`tfm-adapter-bods`](tfm-adapter-bods/README.md) | BODS HTTP requests and SIRI-VM XML decoding | [`tfm-model`](tfm-model/README.md) |
| [`tfm-adapter-stops`](tfm-adapter-stops/README.md) | NaPTAN/NPTG downloads, caching, CSV decoding, and rail-code lookup | [`tfm-model`](tfm-model/README.md) |
| [`tfm-adapter-gtfs`](tfm-adapter-gtfs/README.md) | Regional timetable archive downloads, caching, and decoding | [`tfm-model`](tfm-model/README.md) |
| [`tfm-storage-postgres`](tfm-storage-postgres/README.md) | Catalog, bus timetable, train schedule, platform inventory, and load-state persistence | [`tfm-model`](tfm-model/README.md) |
| [`tfm-proto`](tfm-proto/README.md) | Generated frontend API messages and gRPC service definitions | None |
| [`tfm-server`](tfm-server/README.md) | Application services, caching, protobuf mapping, and Dagger wiring | Model, adapters, proto |

The model has no framework dependencies. Feed adapters return model values and must not depend on
server classes or protobuf. The server binds the adapter to `BoardProvider`; the train-board service
uses that interface, so another provider or a test stub can supply boards without changing the service.
The bus service uses `VehiclePositionProvider`, with retry hints carried by a transport-independent
`FeedException`. Only the server translates model values into the frontend wire contract.

Expected times and delay values can be absent. A cancelled or delayed service without a predicted time
has a status, not a fabricated clock time. Unknown coach loading is distinct from a reported zero.
Times are local `HH:mm` strings; generated timestamps retain the source's ISO representation.
Service and station identifiers remain opaque to the model and scoped to their provider.

The extracted paths cover live train arrivals/departures, the BODS vehicle feed, NaPTAN/NPTG
reference catalogs, and regional GTFS timetables. Polling, refresh scheduling, trip matching, and map
behaviour remain application services. Darwin Kafka transport/decoding is also extracted; live
platform/movement state and schedule batching stay in the server, coordinated independently.
Rail schedule and platform inventory SQL live in `tfm-storage-postgres`. Connection-pool setup,
the complete migration history, and derived bus geometry generation
still live in `tfm-server`. Catalog and bus timetable storage now live in `tfm-storage-postgres`. Extract each behind a model
capability as it is migrated; avoid empty adapter modules and speculative model types.

Build and test every module from this directory:

```sh
mvn verify
```

To build the runnable server with its required modules:

```sh
mvn -pl tfm-server -am package
```

The Dockerfile copies every module POM before dependency resolution and packages the same reactor.

Java formatting is enforced by Spotless during Maven's `validate` phase, including CI's
`mvn verify`. All handwritten main and test sources use Google Java Format, remove unused imports, and
shorten fully qualified types to imports where names do not conflict. Generated sources are excluded. Apply formatting from this directory before committing:

```sh
mvn spotless:apply
```

To check formatting without building:

```sh
mvn spotless:check
```
