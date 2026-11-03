-- Retire the snapshot baseline, preserving schedules received through Kafka.
DELETE FROM darwin_schedule_stops WHERE rid IN
    (SELECT rid FROM darwin_schedules WHERE source = 'file');
DELETE FROM darwin_schedules WHERE source = 'file';
ALTER TABLE darwin_schedules DROP COLUMN source, DROP COLUMN loaded_at;
