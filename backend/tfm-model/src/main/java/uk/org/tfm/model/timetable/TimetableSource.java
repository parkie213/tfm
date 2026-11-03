package uk.org.tfm.model.timetable;

import uk.org.tfm.model.feed.FeedException;

/**
 * A supplied timetable, read one table at a time. Each call opens a fresh {@link RowReader} over
 * that table, so memory stays flat however large the feed is. IDs are source-scoped.
 *
 * <p>Opening a table fails with {@link FeedException} if the source is structurally invalid (a
 * required table or column is missing); malformed individual rows are skipped by the source.
 * Optional tables the source doesn't supply read as empty.
 */
public interface TimetableSource {
  RowReader<Agency> agency() throws FeedException;

  RowReader<Route> routes() throws FeedException;

  RowReader<Journey> trips() throws FeedException;

  RowReader<StopTime> stopTimes() throws FeedException;

  RowReader<ServiceCalendar> calendar() throws FeedException;

  RowReader<CalendarException> calendarDates() throws FeedException;

  RowReader<ShapePoint> shapes() throws FeedException;
}
