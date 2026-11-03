-- Live Darwin push-port SC (schedule) ingest coexists in darwin_schedules alongside the periodic
-- timetable-file load. The file load switches from TRUNCATE-replace to additive append (ON CONFLICT
-- DO NOTHING) so it no longer wipes live-upserted rows; SC rows are upserted (ON CONFLICT DO UPDATE)
-- and kept fresh. Both are bounded by age-based eviction (ssd < today-2). See pushport-pipeline-plan.

-- Provenance + freshness. source defaults to 'file' so the existing file Loader (which doesn't name
-- the column) tags its rows automatically; the SC path sets source='sc' explicitly. last_seen lets
-- eviction/observability tell live-refreshed rows from stale ones.
ALTER TABLE darwin_schedules
    ADD COLUMN IF NOT EXISTS source    TEXT        NOT NULL DEFAULT 'file'
        CHECK (source IN ('file', 'sc')),
    ADD COLUMN IF NOT EXISTS last_seen TIMESTAMPTZ NOT NULL DEFAULT now();

-- Eviction (DELETE ... WHERE ssd < ...) range scan, and the ssd filter in findCallingAt.
CREATE INDEX IF NOT EXISTS darwin_schedules_ssd ON darwin_schedules (ssd);

-- Board lookups now run under continuous SC upserts; make the (tpl, dep/arr) stop lookups
-- index-only by covering rid, so they don't heap-fetch against hot pages.
DROP INDEX IF EXISTS darwin_schedule_stops_tpl_dep;
DROP INDEX IF EXISTS darwin_schedule_stops_tpl_arr;
CREATE INDEX IF NOT EXISTS darwin_schedule_stops_tpl_dep
    ON darwin_schedule_stops (tpl, dep) INCLUDE (rid);
CREATE INDEX IF NOT EXISTS darwin_schedule_stops_tpl_arr
    ON darwin_schedule_stops (tpl, arr) INCLUDE (rid);

-- 30% free space per page so an SC re-issue that touches only non-indexed columns (toc, origin_dep,
-- dest_arr, last_seen) updates in place (HOT) instead of bloating.
ALTER TABLE darwin_schedules SET (fillfactor = 70);
