# Transport for Melton

A local bus and rail information app for Melton Mowbray: departure boards, live buses,
stop search, and route maps. Built with React, TypeScript, Java, and gRPC.

Powered by pies. 🥧

## What it does

- Shows bus timetables and live vehicle locations where data is available.
- Displays train departures, optional arrivals, and operator-supplied coach information.
- Maps stops, bus routes, and live bus locations; selecting a service shows its calling points.
- Finds nearby stops with a one-shot location lookup.
- Includes a **kiosk view** at `?view=tv`, with train and bus boards and a map around a chosen address.
- Shows feed availability and freshness on a data-sources page.

Train calling points and platform occupancy can be enabled through optional Darwin
feeds. There is no live train position estimator or continuous device tracking.
TFM is a personal project, unaffiliated with transport operators or local authorities.

## Run locally

You need Docker with the Compose plugin and an internet connection. The map uses the
[hosted OpenFreeMap Liberty style](https://openfreemap.org/quick_start/); no basemap archive
or API key is needed for it.

From the repository root:

```bash
cp deploy/.env.example deploy/.env
# Optional: add train-board and live-bus credentials to deploy/.env.
cd deploy
docker compose up --build
```

Open **http://localhost:5174** for the map or
**http://localhost:5174/?view=tv** for the kiosk.

The stack runs the frontend, Java server, Envoy, and PostgreSQL/PostGIS. Published ports
bind to localhost. On the first run the backend downloads stop/locality data and the
East Midlands bus timetable; boards and routes populate as those loads complete.
The initial load can take several minutes and use several GB of memory. The Java
container defaults to a 3 GB heap; allow additional memory for Postgres and image builds.

Scheduled bus data needs no credentials. **Live bus and train information requires your
own upstream subscriptions.** Without keys, the map and scheduled bus features remain
available after ingestion, while unconfigured live features show unavailable states.
There is not yet a bundled demo dataset.

To stop, from `deploy/`:

```bash
docker compose down
```

The database volume and downloaded timetable cache persist between runs. Local secrets
and data files are ignored by Git.

### Optional feeds

| Setting | Enables |
| --- | --- |
| `DARWIN_TOKEN` | RDM LDBWS REST live train departures |
| `DARWIN_ARRIVALS_BASE_URL`, optionally `DARWIN_ARRIVALS_TOKEN` | Train arrivals from an arrivals product |
| `BODS_API_KEY` | BODS SIRI-VM live bus positions |
| `DARWIN_KAFKA_*` | Darwin platform occupancy and near-term train schedules |

Train journey details and headcodes use persisted Kafka schedules.
A fresh database gains coverage as those schedules arrive.
See [the environment example](deploy/.env.example) for options.

OSRM road routing is optional. With no `OSRM_URL` configured the app uses GTFS route
shapes. Connecting a separately managed OSRM server remains optional; this repository
does not include road-routing dataset build tools.

## Development

The container setup includes the frontend. For frontend hot reload, start only its
supporting services:

```bash
cd deploy
docker compose up --build postgres tfm-server envoy
```

In another terminal, from the repository root (Node.js 26 and npm):

```bash
cd frontend
npm ci
# Create this file on a fresh checkout; edit it if you already have local settings.
printf 'VITE_BACKEND_URL=http://localhost:8088\n' > .env.local
npm run dev
```

The Vite server uses port **5174**. The API is on **8088**, native gRPC on **9090**,
and local Postgres on **5433** (`tfm` database/user/password).

### Validation

Backend build and tests require JDK 25 and Maven:

```bash
cd backend
mvn verify
```

Frontend type checking and production build:

```bash
cd frontend
npm ci
npm run build
```

Backend tests cover transport-feed parsing, timetable handling, bus progress estimation,
route geometry, and selected service behavior. The frontend currently has type checking
and build validation, but no automated test suite. CI runs both builds.

## Code structure

```text
backend/
  tfm-proto/     Protobuf contract and generated Java types
  tfm-server/    Java services, feed clients, database migrations, and tests
frontend/       React UI, MapLibre maps, and generated TypeScript client types
deploy/         Local Compose stack and Envoy configuration
scripts/        Reference-data maintenance
docs/           Current integration notes and user journeys
```

```text
React / Connect-ES ──gRPC-Web──► Envoy ──gRPC──► Java / Dagger
                                                 ├── PostgreSQL / PostGIS
                                                 └── Transport feeds
```

The backend is one application. Background loaders ingest reference and timetable data;
shared server streams deliver board and bus updates. The frontend renders map overlays
and service details from the same API used by the kiosk.

### API contract

The schema is in [`backend/tfm-proto/src/main/proto/tfm/v1/`](backend/tfm-proto/src/main/proto/tfm/v1/):

| File | Responsibility |
| --- | --- |
| `tfm.proto` | `TfmService` RPC definitions |
| `boards.proto` | Shared board rows, coach information, and board requests/responses |
| `buses.proto` | Live bus snapshots, position diagnostics, and streaming requests |
| `trains.proto` | Platform occupancy and train journey details |
| `stops.proto` | Stops, localities, and search |
| `routes.proto` | Calling points, route geometry, rail network, and map freshness |
| `data_sources.proto` | Feed availability and freshness |
| `common.proto` | Shared coordinates |

All files use the `tfm.v1` protobuf package and `uk.org.tfm.proto.v1` Java package.
Maven generates Java types during the build. After a schema change, regenerate the
committed TypeScript types and validate both applications:

```bash
cd frontend
npm run generate
npm run build
```

Also run `mvn verify` in `backend/`. Commit the schema and generated TypeScript together.

## Data and attribution

Bus data comes from BODS GTFS and SIRI-VM; stops and places from NaPTAN/NPTG; train
boards from RDM LDBWS, with optional Darwin schedules and platforms. Route geometry
uses GTFS and bundled railway reference data. Maps are provided by OpenFreeMap with
OpenStreetMap data.

Coverage and freshness depend on upstream sources. An absent bus marker does not mean
that a service is not running. See [the attribution page](frontend/src/pages/AboutView.tsx)
and [Darwin integration notes](docs/darwin-timetable.md).

The source code is released under the [MIT Licence](LICENSE). Third-party data and assets
retain their own terms and attributions.
