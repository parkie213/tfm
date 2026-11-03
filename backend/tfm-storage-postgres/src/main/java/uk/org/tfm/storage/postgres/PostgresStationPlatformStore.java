package uk.org.tfm.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.storage.StationPlatformStore;
import uk.org.tfm.model.storage.StorageException;

/**
 * Reads/writes {@code station_platforms} (see {@code V18__station_platforms.sql}) — the persistent
 * inventory of platform labels ever observed at each TIPLOC on the Darwin push-port feed.
 *
 * <p>This is the durable backing store for {@code DarwinPlatformService}'s in-memory {@code
 * knownPlatforms} map: {@link #loadAll()} seeds it on boot (so the station board shows the full
 * platform layout immediately, even before the live feed warms up or when it is disabled), and
 * {@link #upsertBatch} flushes newly-discovered labels back. The inventory is append-mostly and
 * idempotent — a re-observed {@code (tiploc, label)} pair only bumps {@code last_seen}.
 */
public final class PostgresStationPlatformStore implements StationPlatformStore {

  private static final Logger log = LoggerFactory.getLogger(PostgresStationPlatformStore.class);

  private static final String LOAD_ALL_SQL = "SELECT tiploc, platform_label FROM station_platforms";

  private static final String UPSERT_SQL =
      "INSERT INTO station_platforms (tiploc, platform_label, last_seen) VALUES (?, ?, ?) "
          + "ON CONFLICT (tiploc, platform_label) DO UPDATE SET last_seen = EXCLUDED.last_seen";

  private static final int BATCH_SIZE = 500;

  private final DataSource dataSource;

  public PostgresStationPlatformStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Loads the entire platform inventory as a {@code TIPLOC -> set of platform labels} map. Called
   * once on {@code DarwinPlatformService.start()} to seed the in-memory inventory. The table is
   * small (a few thousand rows across ~2,600 stations) so a full scan is correct.
   */
  private Map<String, Set<String>> queryAll() throws SQLException {
    Map<String, Set<String>> result = new HashMap<>();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(LOAD_ALL_SQL);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        result.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
      }
    }
    return result;
  }

  /**
   * Upserts a batch of observed platform labels in one transaction. {@code observations} is {@code
   * TIPLOC -> labels}; every row's {@code last_seen} is set to {@code observedAt}, and {@code
   * first_seen} defaults to {@code now()} on first insert. A no-op for an empty map.
   *
   * @throws SQLException if the batch fails; the transaction is rolled back so the caller can
   *     retry.
   */
  private void writeBatch(Map<String, Set<String>> observations, Instant observedAt)
      throws SQLException {
    if (observations.isEmpty()) {
      return;
    }
    Timestamp ts = Timestamp.from(observedAt);
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
        int pending = 0;
        for (Map.Entry<String, Set<String>> e : observations.entrySet()) {
          for (String label : e.getValue()) {
            ps.setString(1, e.getKey());
            ps.setString(2, label);
            ps.setTimestamp(3, ts);
            ps.addBatch();
            if (++pending >= BATCH_SIZE) {
              ps.executeBatch();
              pending = 0;
            }
          }
        }
        if (pending > 0) {
          ps.executeBatch();
        }
        conn.commit();
      } catch (SQLException ex) {
        conn.rollback();
        throw ex;
      }
    }
  }

  @Override
  public Map<String, Set<String>> loadAll() throws StorageException {
    try {
      return queryAll();
    } catch (SQLException e) {
      throw new StorageException("Platform inventory read failed", e);
    }
  }

  @Override
  public void upsertBatch(Map<String, Set<String>> observations, Instant observedAt)
      throws StorageException {
    try {
      writeBatch(observations, observedAt);
    } catch (SQLException e) {
      throw new StorageException("Platform inventory write failed", e);
    }
  }
}
