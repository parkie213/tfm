package uk.org.tfm.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import javax.sql.DataSource;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.storage.TimetableStore;
import uk.org.tfm.model.timetable.*;

/** Implements the timetable storage port by composing focused package-private query classes. */
public final class PostgresTimetableStore implements TimetableStore {
  private final DataSource dataSource;
  private final TimetableDepartureQueries departures;
  private final TimetableJourneyQueries journeys;
  private final TimetableTripMatcher matcher;

  public PostgresTimetableStore(DataSource dataSource) {
    this.dataSource = dataSource;
    departures = new TimetableDepartureQueries(dataSource);
    journeys = new TimetableJourneyQueries(dataSource);
    matcher = new TimetableTripMatcher(dataSource);
  }

  // ---- DataSourceRegistry: count query ----

  /**
   * Returns the total number of rows in the {@code gtfs_trips} table. Used by the data-source
   * inventory; the caller is responsible for TTL-caching this to avoid per-request scans.
   */
  private long queryTripCount() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM gtfs_trips");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Override
  public ScheduledBoard getDepartures(String stopId, int rows, int lookaheadMinutes, String date)
      throws StorageException {
    try {
      return departures.queryDepartures(stopId, rows, lookaheadMinutes, date);
    } catch (SQLException e) {
      throw new StorageException("Timetable board query failed", e);
    }
  }

  @Override
  public JourneyDetails getServiceStops(String tripId) throws StorageException {
    try {
      return journeys.queryServiceStops(tripId);
    } catch (SQLException e) {
      throw new StorageException("Journey query failed", e);
    }
  }

  @Override
  public JourneyDetails getRouteStops(String shortName, String longName, String operator)
      throws StorageException {
    try {
      return journeys.queryRouteStops(shortName, longName, operator);
    } catch (SQLException e) {
      throw new StorageException("Route query failed", e);
    }
  }

  @Override
  public Map<JourneyKey, String> matchTripIds(Collection<JourneyKey> journeys)
      throws StorageException {
    try {
      return matcher.queryMatchTripIds(journeys);
    } catch (SQLException e) {
      throw new StorageException("Journey matching failed", e);
    }
  }

  @Override
  public long tripCount() throws StorageException {
    try {
      return queryTripCount();
    } catch (SQLException e) {
      throw new StorageException("Timetable count failed", e);
    }
  }

  @Override
  public boolean hasTimetable() throws StorageException {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement("SELECT EXISTS(SELECT 1 FROM gtfs_routes)");
        ResultSet rs = ps.executeQuery()) {
      return rs.next() && rs.getBoolean(1);
    } catch (SQLException e) {
      throw new StorageException("Timetable existence query failed", e);
    }
  }

  @Override
  public void bulkLoad(TimetableSource source) throws StorageException, FeedException {
    try {
      new PostgresTimetableImporter(dataSource).replace(source);
    } catch (SQLException e) {
      throw new StorageException("Timetable import failed", e);
    }
  }
}
