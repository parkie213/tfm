package uk.org.tfm.model.storage;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import uk.org.tfm.model.rail.ScheduleRecord;
import uk.org.tfm.model.rail.TrainCallingPoint;
import uk.org.tfm.model.rail.TrainSchedule;

/** Durable train schedules. Reads degrade to empty on storage failure; eviction is best-effort. */
public interface TrainScheduleStore {
  Optional<TrainSchedule> byRid(String rid);

  Optional<String> headcodeForRid(String rid);

  List<TrainCallingPoint> callingPoints(String rid);

  List<TrainSchedule> findCallingAt(String tpl, String hhmm, boolean isArrival, LocalDate date);

  void upsertLiveBatch(List<ScheduleRecord> records) throws StorageException;

  long evictOldSchedules(LocalDate cutoff);
}
