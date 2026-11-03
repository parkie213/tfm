-- Drop the unused BIGSERIAL surrogate PK from gtfs_route_trunks.
--
-- `id` is never read — the table is queried only by GiST bounding box (RouteShapeDao.trunksInViewport
-- selects geom + the route arrays, never id) and inserted without it. It exists only as a surrogate
-- key. The Phase 5 atomic-visibility swap rebuilds geometry into a *_staging table and promotes it
-- with DROP old + ALTER ... RENAME; a BIGSERIAL's OWNED-BY sequence would be dropped with the old
-- table during that swap and break the renamed staging table's default. With no id/sequence the swap
-- is a plain table rename, identical to gtfs_route_lanes. Dropping the column also drops its PK
-- constraint and owned sequence.
ALTER TABLE gtfs_route_trunks DROP COLUMN IF EXISTS id;
