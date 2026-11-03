# Development

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

## Validation

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
  tfm-model/               Shared transport model and storage interfaces
  tfm-adapter-*/           Bus, rail, timetable, and reference-data adapters
  tfm-storage-postgres/    PostgreSQL stores and streaming timetable importer
  tfm-proto/               Protobuf contract and generated Java types
  tfm-server/              Application wiring, services, migrations, and integration tests
frontend/                 React UI, MapLibre maps, and generated TypeScript client types
deploy/                   Local Compose stack and Envoy configuration
scripts/                  Reference-data maintenance
docs/                     Integration notes and manual smoke checks
```

The backend is one application. Background loaders ingest reference and timetable data;
shared server streams deliver board and bus updates. The frontend renders map overlays
and service details from the same API used by the kiosk.

### API contract

The schema is in [`backend/tfm-proto/src/main/proto/tfm/v1/`](../backend/tfm-proto/src/main/proto/tfm/v1/):

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
