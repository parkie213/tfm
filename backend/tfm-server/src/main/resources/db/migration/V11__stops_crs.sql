-- National Rail CRS code on stops. Populated by NaptanLoader from the bundled rail-references.csv
-- (NaPTAN AnnotatedRailRef TIPLOC/CRS), joined to RLY stops by TIPLOC and RSE stops by name/proximity.
-- Empty string for bus/metro and any rail stop with no Network Rail board. Drives the live train board.
ALTER TABLE stops ADD COLUMN IF NOT EXISTS crs TEXT NOT NULL DEFAULT '';

-- stops_staging is created on-demand by StopDao.ensureStagingTable (CREATE TABLE IF NOT EXISTS), so a
-- pre-existing staging table from older code would lack crs and break the 9-column COPY. Patch it too.
ALTER TABLE IF EXISTS stops_staging ADD COLUMN IF NOT EXISTS crs TEXT NOT NULL DEFAULT '';
