-- Bookkeeping for the GTFS bulk loader's freshness gates (see GtfsBulkLoader.load). Lets each load
-- SKIP work whose inputs are unchanged instead of re-deriving everything on every restart/redeploy.
--
--   feed_version : content hash of the cached GTFS zip(s). The core gtfs_* tables derive only from
--                  this, so an unchanged feed means the TRUNCATE+COPY can be skipped.
--   geometry_key : sha256(feed_version + osrm_build_id + geometry_algo_version). The derived
--                  route_shapes/lanes/trunks geometry depends on all three; unchanged means the
--                  (OSRM-heavy, minutes-long) rebuild can be skipped.
--
-- Single row, guarded by a boolean PK that can only be true. This table co-locates with the derived
-- tables in the same database, so a wiped/fresh DB resets it together with them (NULL columns =>
-- "nothing loaded" => the next load runs in full). To force a rebuild by hand, clear the columns:
--   UPDATE load_state SET geometry_key = NULL;   -- next load re-derives geometry
CREATE TABLE IF NOT EXISTS load_state (
  id                boolean PRIMARY KEY DEFAULT true CHECK (id),
  feed_version      text,
  feed_loaded_at    timestamptz,
  geometry_key      text,
  geometry_built_at timestamptz
);

-- Seed the single row so the loader can UPDATE it unconditionally.
INSERT INTO load_state (id) VALUES (true) ON CONFLICT (id) DO NOTHING;
