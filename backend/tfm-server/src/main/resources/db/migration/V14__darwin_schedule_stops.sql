-- Public calling points for each Darwin Timetable journey: the passenger-facing stop sequence
-- (pta/ptd non-blank only — passing points with working-only times are excluded). Loaded by
-- DarwinTimetableCli alongside darwin_schedules, within the same transaction.
--
-- No FK to darwin_schedules: both tables are truncated together in beginReplace, and an FK would
-- require TRUNCATE CASCADE ordering for no correctness benefit.
CREATE TABLE IF NOT EXISTS darwin_schedule_stops (
  rid  TEXT    NOT NULL,              -- RTTI unique id; matches darwin_schedules.rid
  seq  INT     NOT NULL,              -- 1-based sequence within the journey (public stops only)
  tpl  TEXT    NOT NULL,              -- TIPLOC of the calling point
  arr  TEXT    NOT NULL DEFAULT '',   -- public arrival time, HH:MM[:SS], or '' at origin
  dep  TEXT    NOT NULL DEFAULT '',   -- public departure time, HH:MM[:SS], or '' at destination
  PRIMARY KEY (rid, seq)
);

-- Board-row join: find journeys calling at a TIPLOC at a given public departure (or arrival) time.
CREATE INDEX IF NOT EXISTS darwin_schedule_stops_tpl_dep ON darwin_schedule_stops (tpl, dep);
CREATE INDEX IF NOT EXISTS darwin_schedule_stops_tpl_arr ON darwin_schedule_stops (tpl, arr);
