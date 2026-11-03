-- Mark where each overlay route's geometry came from.
--   'gtfs' — the operator's published shape (rebuilt from gtfs_shapes every load).
--   'osrm' — road-snapped from the route's stop sequence for operators that publish no shape
--            (e.g. the Arriva Melton 5A, the MC minibuses). These rows are EXPENSIVE to build
--            (one OSRM call each) so they are persisted and reused across reloads, never truncated.
ALTER TABLE gtfs_route_shapes ADD COLUMN source TEXT NOT NULL DEFAULT 'gtfs';
