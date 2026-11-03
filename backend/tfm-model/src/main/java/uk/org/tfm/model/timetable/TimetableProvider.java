package uk.org.tfm.model.timetable;

import java.util.List;
import uk.org.tfm.model.feed.FeedException;

/** Supplies a versioned timetable for the configured regions, without exposing archive paths. */
public interface TimetableProvider {
  List<String> regions();

  TimetableSnapshot fetch() throws FeedException;
}
