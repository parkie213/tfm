-- GTFS route geometry (the operator's exact bus path). Loaded only when GTFS_LOAD_SHAPES=true
-- (on by default at the regional scale; ~0.5 GB for East Midlands). Refreshed with the rest of GTFS.
ALTER TABLE gtfs_trips ADD COLUMN shape_id TEXT NOT NULL DEFAULT '';

CREATE TABLE gtfs_shapes (
    shape_id TEXT             NOT NULL,
    seq      INTEGER          NOT NULL,
    lat      DOUBLE PRECISION NOT NULL,
    lng      DOUBLE PRECISION NOT NULL
);

CREATE INDEX gtfs_shapes_id_seq ON gtfs_shapes (shape_id, seq);
