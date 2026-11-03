package uk.org.tfm.server.gtfs;

import java.util.Optional;
import java.util.function.IntFunction;
import org.postgresql.ds.PGSimpleDataSource;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.timetable.*;
import uk.org.tfm.storage.postgres.PostgresTimetableStore;

/**
 * A generated timetable: {@code stopTimes} rows produced on the fly (each with fresh ID strings, as
 * a parser would make), optionally failing after {@code failAfter} rows. Other tables are empty.
 *
 * <p>{@link #main} imports one into Postgres; {@link GtfsImportRoundTripTest} runs it in a child
 * JVM with a small heap to prove the import doesn't hold the feed in memory.
 */
final class SyntheticTimetable implements TimetableSource {
  private final int stopTimes;
  private final int failAfter;

  SyntheticTimetable(int stopTimes, int failAfter) {
    this.stopTimes = stopTimes;
    this.failAfter = failAfter;
  }

  /** Deterministic row {@code i}; the same function verifies what was loaded. */
  static StopTime row(int i) {
    return new StopTime("trip-" + (i / 20), "stop-" + (i % 997), i % 20 + 1, 21_600 + i % 64_800);
  }

  @Override
  public RowReader<StopTime> stopTimes() {
    return generate(stopTimes, SyntheticTimetable::row);
  }

  private <T> RowReader<T> generate(int count, IntFunction<T> make) {
    return new RowReader<>() {
      private int next;

      @Override
      public T next() throws FeedException {
        if (next == failAfter) {
          throw new FeedException(
              "synthetic failure at row " + next,
              FeedException.Kind.INVALID_RESPONSE,
              Optional.empty());
        }
        return next < count ? make.apply(next++) : null;
      }

      @Override
      public void close() {}
    };
  }

  @Override
  public RowReader<Agency> agency() {
    return RowReader.empty();
  }

  @Override
  public RowReader<Route> routes() {
    return RowReader.empty();
  }

  @Override
  public RowReader<Journey> trips() {
    return RowReader.empty();
  }

  @Override
  public RowReader<ServiceCalendar> calendar() {
    return RowReader.empty();
  }

  @Override
  public RowReader<CalendarException> calendarDates() {
    return RowReader.empty();
  }

  @Override
  public RowReader<ShapePoint> shapes() {
    return RowReader.empty();
  }

  /** Args: jdbcUrl user password rows. Exits non-zero (or with an OOM) on failure. */
  public static void main(String[] args) throws Exception {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(args[0]);
    ds.setUser(args[1]);
    ds.setPassword(args[2]);
    new PostgresTimetableStore(ds).bulkLoad(new SyntheticTimetable(Integer.parseInt(args[3]), -1));
  }
}
