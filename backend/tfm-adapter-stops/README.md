# tfm-adapter-stops

The reference-catalog adapter for NaPTAN stops and NPTG localities. Production dependencies are
`tfm-model` and the SLF4J logging API. HTTP, filesystem caching, and CSV parsing use the JDK;
this module has no server, database, protobuf, or Dagger dependency.

- `NaptanClient` implements `CatalogProvider<StopDefinition>`. It downloads national or selected
  ATCO-area CSV data, keeps active bus/rail stops with usable GB coordinates, and resolves National
  Rail CRS codes using the bundled `naptan/rail-references.csv` resource.
- `NptgClient` implements `CatalogProvider<LocalityDefinition>`. It downloads the locality CSV and
  keeps named entries with usable British National Grid coordinates. Grid-to-WGS84 conversion
  remains in storage.

Both clients parse columns by name and share the quote-aware CSV splitter. They reuse cached CSVs
for 24 hours and download replacements through a temporary file and atomic move. Download/cache
failures become `FeedException.Kind.UNAVAILABLE`; malformed CSV headers become `INVALID_RESPONSE`.
The exposed timestamp is the cached file's modification time, not a successful database-import time.

The server supplies the cache directory (`GTFS_CACHE_DIR`), optional NaPTAN areas (`NAPTAN_AREAS`),
and NPTG URL (`NPTG_URL`) through constructors. Clients do not read environment variables or start
threads. Refresh scheduling, database imports, search, and frontend mapping remain in `tfm-server`.

The rail-reference resource is also consumed from the application classpath for train-platform
scope, station names, and calling-point coordinates.

Build and run tests from `backend/` with JDK 25 and Maven:

```sh
mvn -pl tfm-adapter-stops -am verify
```

Tests use temporary cached CSVs and the bundled rail references; they need no network or database.
See [the backend module guide](../README.md) for the dependency layout.
