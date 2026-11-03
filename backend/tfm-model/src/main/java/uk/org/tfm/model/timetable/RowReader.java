package uk.org.tfm.model.timetable;

import uk.org.tfm.model.feed.FeedException;

/**
 * Rows of one timetable table, read lazily so a load never holds the whole table in memory. Callers
 * must close it, which releases the underlying archive.
 */
public interface RowReader<T> extends AutoCloseable {
  /** The next row, or {@code null} when the table is exhausted. */
  T next() throws FeedException;

  @Override
  void close();

  /** A reader with no rows (an optional table that the source doesn't supply). */
  static <T> RowReader<T> empty() {
    return new RowReader<>() {
      @Override
      public T next() {
        return null;
      }

      @Override
      public void close() {}
    };
  }
}
