-- Product analytics: records each stop-search query, INCLUDING zero-result ones.
-- Zero-result rows are the high-value signal — they are the stops/names users wanted
-- that we could not answer (coverage gaps, naming mismatches, unmet demand for regions
-- not yet loaded). See SearchEventDao.
--
-- Best-effort telemetry, NOT a source of truth: rows are written fire-and-forget off the
-- request path and may be dropped under write saturation. Never read this for correctness.
--
-- Retention: grows unbounded. At this app's volume that is fine for a long time; prune
-- periodically if it ever matters, e.g.
--   DELETE FROM search_events WHERE searched_at < now() - INTERVAL '180 days';
CREATE TABLE search_events (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    searched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    query        TEXT        NOT NULL,           -- trimmed, as the user typed it (casing preserved)
    result_count INTEGER     NOT NULL            -- rows returned, capped at the requested limit
);

-- Time-bounded analytics ("searches in the last 7 days") and retention pruning.
CREATE INDEX search_events_searched_at_ix ON search_events (searched_at DESC);

-- Headline query is GROUP BY a normalised query for top-N searches; index the lower-cased form.
CREATE INDEX search_events_query_lower_ix ON search_events (lower(query));
