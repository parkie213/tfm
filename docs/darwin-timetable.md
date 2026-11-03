# Darwin schedules and platform boards

Live train departure boards use the RDM LDBWS REST product. It needs a `DARWIN_TOKEN`;
arrivals need an arrivals-product URL and optionally a separate key.

Train journey details and platform enrichment use schedules stored in PostgreSQL.
Configure the Darwin Kafka feed using the `DARWIN_KAFKA_*` settings. Use the broker,
topic, credentials, and group supplied for your subscription. Schedule records supply
headcodes, origins, destinations, and public calling points; platform/movement records
contribute to the platform board. Only journeys calling at an in-scope location are retained.

The feed coordinator dispatches schedules independently of platform maintenance. Schedules are
persisted every 60 seconds, flushed again on shutdown, and survive restarts.
A fresh database fills as Kafka schedules arrive. A consumer with no valid committed offset
starts at `latest`, so it does not automatically reconstruct a complete timetable.
Before a schedule is available, its headcode or journey details may be unavailable.

Clicking a train-board row resolves a journey by station, scheduled time, and destination.
The server returns calling points and their coordinates; an unmatched journey produces an
explicit unavailable state. It does not imply that the train is cancelled.

Arrival and departure boards show only the live REST provider response, independently of Kafka.
Short or empty boards remain short or empty; local schedules do not add rows.

Platform boards combine the live REST board with the optional push-port platform
inventory and confirmation/arrival signals. There is no train-location stream or
GPS/interpolation feature.

Timetable XML file imports are retired. Migration V24 removes file-sourced schedules
and obsolete snapshot metadata while preserving Kafka-sourced schedules and calling points.
