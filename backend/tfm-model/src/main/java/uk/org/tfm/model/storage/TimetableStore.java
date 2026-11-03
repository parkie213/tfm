package uk.org.tfm.model.storage;

import java.util.Collection;
import java.util.Map;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.timetable.*;

/** Persistence and calendar-valid queries over a supplied bus timetable. */
public interface TimetableStore extends TripCountSource {
  ScheduledBoard getDepartures(String stopId, int rows, int lookaheadMinutes, String date)
      throws StorageException;

  JourneyDetails getServiceStops(String tripId) throws StorageException;

  JourneyDetails getRouteStops(String shortName, String longName, String operator)
      throws StorageException;

  Map<JourneyKey, String> matchTripIds(Collection<JourneyKey> journeys) throws StorageException;

  boolean hasTimetable() throws StorageException;

  /**
   * Atomically replaces the stored timetable with {@code source}, streaming each table. On any
   * failure the previous timetable is left in place: a {@link FeedException} means the source was
   * invalid, a {@link StorageException} that the write failed.
   */
  void bulkLoad(TimetableSource source) throws StorageException, FeedException;
}
