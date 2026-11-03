# tfm-adapter-darwin

The live train-board adapter for Darwin's Rail Data Marketplace LDBWS REST API.
It depends on `tfm-model` and Jackson, with no dependency on the server or protobuf.

`DarwinClient` implements `BoardProvider` and `FeedHealth`. It makes authenticated HTTP requests,
normalizes configured base URLs, applies request timeouts, and records request health.
`DarwinBoardParser` converts provider JSON into model boards, including reported times,
cancellations, origins/destinations, station messages, and coach formation/loading.
Provider status text is converted into model status values; it is not used as a clock time.

The server constructs the client and supplies configuration:

- `DARWIN_BASE_URL` and `DARWIN_TOKEN`: departure endpoint and API key.
- `DARWIN_ARRIVALS_BASE_URL` and `DARWIN_ARRIVALS_TOKEN`: optional arrival endpoint and key.
- `DARWIN_TIMEOUT_MS`: request timeout.

The client receives these values through its constructor; it does not read environment variables.
The server defaults the arrival key to the departure key. Requesting arrivals without an arrival
endpoint raises an error. Both board types use Darwin; separate URLs reflect the configured API products.

Caching and protobuf mapping belong to the server. Darwin Kafka is separate in
[`tfm-adapter-darwin-kafka`](../tfm-adapter-darwin-kafka/README.md); local timetable ingestion remains in the server.

Build and run parser tests from `backend/` with JDK 25 and Maven:

```sh
mvn -pl tfm-adapter-darwin -am verify
```

Tests use JSON fixtures and need no API credentials. See [the backend module guide](../README.md).
