# tfm-adapter-bods

The live bus-position adapter for the BODS SIRI-VM feed. Its only production dependency is
`tfm-model`; HTTP and XML parsing use the JDK. It has no server, database, protobuf, or Dagger dependency.

`SiriVmClient` implements `VehiclePositionProvider`. It fetches a configured bounding box from BODS,
with a 10-second connection timeout and 20-second request timeout. `SiriVmParser` validates and
parses the XML, rejects external entities, skips observations without usable coordinates, and returns
model vehicle positions. It converts origin-departure times to Europe/London local time and preserves
the explicit service date when supplied, falling back to the origin departure's local date.

The server constructs the adapter using `BODS_API_KEY` and `BODS_BBOX`. The bounding box uses
`minLongitude,minLatitude,maxLongitude,maxLatitude`. The adapter receives both through its
constructor; it does not read environment variables. HTTP requests put the API key in the query,
but exception messages omit the request URL and key.

Failures cross the boundary as `FeedException`: HTTP 429 becomes `RATE_LIMITED`, other HTTP/network
failures become `UNAVAILABLE`, and bad XML/non-SIRI responses become `INVALID_RESPONSE`. Numeric
`Retry-After` values are exposed as a duration. HTTP-date retry values are not interpreted; the
server supplies its normal fallback delay.

Polling, backoff, freshness, vehicle history, GTFS trip matching, road snapping, and protobuf mapping
remain in `tfm-server`. This adapter reports observations without estimating delays or persisting fixes.

Build and run tests from `backend/` with JDK 25 and Maven:

```sh
mvn -pl tfm-adapter-bods -am verify
```

Tests use XML fixtures and an in-memory HTTP client stub; no API credentials or network are needed.
See [the backend module guide](../README.md) for the module layout.
