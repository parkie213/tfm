package uk.org.tfm.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import uk.org.tfm.model.storage.LoadState;
import uk.org.tfm.model.storage.LoadStateStore;
import uk.org.tfm.model.storage.StorageException;

/**
 * Reads/writes the single {@code load_state} row that drives the GTFS loader's freshness gates (see
 * {@code V15__load_state.sql} and {@code TimetableRefreshService}). Nulls mean "nothing loaded
 * yet".
 */
public final class PostgresLoadStateStore implements LoadStateStore {

  private final DataSource dataSource;

  public PostgresLoadStateStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** The single row's current values (all-null if the row is somehow absent). */
  private LoadState queryState() throws SQLException {
    String sql =
        "SELECT feed_version, feed_loaded_at, geometry_key, geometry_built_at "
            + "FROM load_state WHERE id = true";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        return new LoadState(
            rs.getString(1), toInstant(rs.getTimestamp(2)),
            rs.getString(3), toInstant(rs.getTimestamp(4)));
      }
      return new LoadState(null, null, null, null);
    }
  }

  /** Records that the core gtfs_* tables now reflect {@code feedVersion}. */
  private void updateFeed(String feedVersion, Instant loadedAt) throws SQLException {
    update(
        "UPDATE load_state SET feed_version = ?, feed_loaded_at = ? WHERE id = true",
        feedVersion,
        loadedAt);
  }

  /**
   * Records that the derived geometry now reflects {@code geometryKey}, on a CALLER-SUPPLIED
   * connection (does not commit). The geometry-generation swap calls this inside its swap
   * transaction so the fingerprint and the live tables flip in one commit — they can never
   * disagree.
   */
  private void setGeometry(Connection conn, String geometryKey, Instant builtAt)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "UPDATE load_state SET geometry_key = ?, geometry_built_at = ? WHERE id = true")) {
      ps.setString(1, geometryKey);
      ps.setTimestamp(2, Timestamp.from(builtAt));
      ps.executeUpdate();
    }
  }

  private void update(String sql, String value, Instant when) throws SQLException {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, value);
      ps.setTimestamp(2, Timestamp.from(when));
      ps.executeUpdate();
    }
  }

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private void publish(String geometryKey, Instant builtAt) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try (Statement st = conn.createStatement()) {
        st.execute("SET LOCAL lock_timeout = '2s'");
        promoteIfStaged(st, "gtfs_route_trunks");
        setGeometry(conn, geometryKey, builtAt);
        conn.commit();
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(true);
      }
    }
  }

  /**
   * Within the swap txn: replace {@code live} with its staging table, restoring canonical names.
   */
  private static void promoteIfStaged(Statement st, String live) throws SQLException {
    String staging = live + "_staging";
    boolean staged;
    try (ResultSet rs = st.executeQuery("SELECT to_regclass('" + staging + "') IS NOT NULL")) {
      staged = rs.next() && rs.getBoolean(1);
    }
    if (!staged) {
      return; // this table wasn't rebuilt this cycle (e.g. OSRM disabled) — leave it as-is
    }
    st.execute("DROP TABLE " + live);
    st.execute("ALTER TABLE " + staging + " RENAME TO " + live);
    st.execute("ALTER INDEX " + staging + "_geom RENAME TO " + live + "_geom");
  }

  @Override
  public LoadState read() throws StorageException {
    try {
      return queryState();
    } catch (SQLException e) {
      throw new StorageException("Load-state query failed", e);
    }
  }

  @Override
  public void setFeed(String version, Instant loadedAt) throws StorageException {
    try {
      updateFeed(version, loadedAt);
    } catch (SQLException e) {
      throw new StorageException("Load-state update failed", e);
    }
  }

  @Override
  public void publishGeometry(String key, Instant builtAt) throws StorageException {
    try {
      publish(key, builtAt);
    } catch (SQLException e) {
      throw new StorageException("Geometry publication failed", e);
    }
  }
}
