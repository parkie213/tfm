-- Darwin Timetable schedules: headcode (signalling id) -> origin/destination, for resolving a TRUST
-- train to its O/D. Loaded by DarwinTimetableCli from the RDM "Darwin Timetable Files" product
-- (RDG, OGL3) — a ~48h pushport snapshot derived from the Network Rail CIF, delivered via SFTP.
--
-- Full-snapshot semantics: each ingest replaces the whole table (the file is a complete picture of
-- the covered window), so no per-row history is kept. `rid` (RTTI unique id) is the natural key.
CREATE TABLE IF NOT EXISTS darwin_schedules (
  rid        TEXT PRIMARY KEY,             -- RTTI unique id (per dated run)
  uid        TEXT NOT NULL,                -- CIF/schedule UID; joins a TRUST activation's train_uid
  train_id   TEXT NOT NULL,                -- signalling id / headcode, e.g. 1F77
  ssd        DATE NOT NULL,                -- schedule start date
  toc        TEXT NOT NULL DEFAULT '',     -- operating TOC code (e.g. EM)
  origin_tpl TEXT NOT NULL DEFAULT '',     -- origin TIPLOC
  dest_tpl   TEXT NOT NULL DEFAULT '',     -- destination TIPLOC
  origin_dep TEXT NOT NULL DEFAULT '',     -- public (else working) departure at origin, HH:MM[:SS]
  dest_arr   TEXT NOT NULL DEFAULT '',     -- public (else working) arrival at destination
  loaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Answers "what is headcode 1F77" (bare lookup) and the TRUST train_uid join (deferred feature).
CREATE INDEX IF NOT EXISTS darwin_schedules_train_id ON darwin_schedules (train_id);
CREATE INDEX IF NOT EXISTS darwin_schedules_uid ON darwin_schedules (uid);
