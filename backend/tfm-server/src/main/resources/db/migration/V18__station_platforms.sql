-- Persistent inventory of platform labels observed at each TIPLOC on the Darwin push-port feed.
-- Durable backing store for DarwinPlatformService.knownPlatforms: seeded on boot so the station
-- board shows the full platform layout immediately (and even when the live feed is disabled), and
-- flushed back as new labels are discovered. Append-mostly and idempotent — a re-observed
-- (tiploc, platform_label) pair only bumps last_seen.
--
-- The label inventory is intentionally independent of darwin_schedules: a platform label needs no
-- rid/headcode resolution, only the TIPLOC. The PK doubles as the covering index for the only read
-- (all labels for one TIPLOC), so no secondary index is needed.
CREATE TABLE IF NOT EXISTS station_platforms (
    tiploc          TEXT        NOT NULL,
    platform_label  TEXT        NOT NULL,
    first_seen      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tiploc, platform_label)
);
