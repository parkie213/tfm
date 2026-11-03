package uk.org.tfm.model.storage;

/** Number of imported timetable journeys, independent of storage implementation. */
public interface TripCountSource {
  long tripCount() throws StorageException;
}
