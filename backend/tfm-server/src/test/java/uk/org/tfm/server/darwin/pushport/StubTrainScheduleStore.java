package uk.org.tfm.server.darwin.pushport;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import uk.org.tfm.model.rail.*;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.storage.TrainScheduleStore;

class StubTrainScheduleStore implements TrainScheduleStore {
  public Optional<TrainSchedule> byRid(String rid) {
    return Optional.empty();
  }

  public Optional<String> headcodeForRid(String rid) {
    return Optional.empty();
  }

  public List<TrainCallingPoint> callingPoints(String rid) {
    return List.of();
  }

  public List<TrainSchedule> findCallingAt(
      String tpl, String time, boolean arrival, LocalDate date) {
    return List.of();
  }

  public void upsertLiveBatch(List<TrainScheduleDetails> records) throws StorageException {}

  public long evictOldSchedules(LocalDate cutoff) {
    return 0;
  }
}
