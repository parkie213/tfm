# tfm-adapter-darwin-kafka

The adapter for the Rail Data Marketplace Darwin Real Time push-port Kafka feed, separate from
the HTTP live-board adapter. Production dependencies are `tfm-model`, Kafka clients, Jackson, and
SLF4J. It has no server, database, protobuf, or Dagger dependency.

`DarwinKafkaPlatformFeed` implements the model's `TrainEventFeed`. It creates a consumer, subscribes
to the configured topic, polls on a daemon thread, and delivers serialized platform, movement, and
schedule callbacks. It reconnects with capped exponential backoff. Stop requests wake and interrupt
the consumer thread, which closes the consumer; starting an already-running feed is a no-op.

`PushportParser` decodes the RDM JSON topic's JMS envelope (`bytes` contains a JSON string), including
object-or-array elements, both live `uR` and snapshot `sR` containers, platform suppression and
confirmation, movement actuals/estimates, and schedules with public calling points. Malformed messages
are dropped. A supplied location-scope predicate filters updates without depending on server station
indexes or bundled resources. One rejected callback does not prevent delivery of other events.

The server constructs `DarwinKafkaSettings` and supplies the scope predicate. The adapter receives
these through its constructor, without reading application environment variables. Settings correspond
to `DARWIN_KAFKA_BOOTSTRAP`, `DARWIN_KAFKA_TOPIC`, `DARWIN_KAFKA_KEY`, `DARWIN_KAFKA_SECRET`,
`DARWIN_KAFKA_GROUP`, and the `DARWIN_KAFKA_ENABLED` switch. Missing credentials or a disabled flag
make the feed a no-op. Settings diagnostic text redacts credentials.

Consumer properties preserve SASL_SSL/PLAIN authentication, the exact issued consumer-group ID,
`auto.offset.reset=latest`, and automatic offset commits. This extraction does not introduce
transactional processing or an exactly-once delivery guarantee. The server still owns platform and
movement indexes, merge/TTL rules, schedule buffering/persistence, and frontend board behaviour.

Build and test from `backend/` with JDK 25 and Maven:

```sh
mvn -pl tfm-adapter-darwin-kafka -am verify
```

Tests use message fixtures and Kafka's `MockConsumer`, without a broker or credentials. They cover
parsing, scope filtering, authentication/offset configuration, disabled behaviour, callback isolation,
and start/delivery/stop lifecycle. They do not test live broker connectivity or ACLs.

A diagnostic sampling CLI is included in the packaged server JAR. With the environment configured,
run from `backend/`:

```sh
java -cp tfm-server/target/tfm-server.jar uk.org.tfm.adapter.darwin.kafka.DarwinKafkaSampleCli 3
```

Only this CLI reads environment variables itself. It manually assigns partitions, seeks to the live
tail, and disables commits so sampling does not rebalance the application's consumer group or advance
its committed offsets. It prints raw messages and exits after the requested count or a 60-second wait.

See [the backend module guide](../README.md).
