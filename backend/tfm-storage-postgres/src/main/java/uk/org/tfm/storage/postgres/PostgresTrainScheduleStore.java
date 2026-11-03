package uk.org.tfm.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.rail.ScheduleRecord;
import uk.org.tfm.model.rail.TrainCallingPoint;
import uk.org.tfm.model.rail.TrainSchedule;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.model.storage.TrainScheduleStore;

/** Stores Kafka schedule records and serves train journey and platform queries. */
public final class PostgresTrainScheduleStore implements TrainScheduleStore {

  private static final Logger log = LoggerFactory.getLogger(PostgresTrainScheduleStore.class);

  private static final String INSERT_STOP_SQL =
      "INSERT INTO darwin_schedule_stops (rid, seq, tpl, arr, dep) "
          + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (rid, seq) DO NOTHING";

  // Re-issued Kafka schedules replace the previous header and calling points.
  private static final String UPSERT_SQL =
      "INSERT INTO darwin_schedules "
          + "(rid, uid, train_id, ssd, toc, origin_tpl, dest_tpl, origin_dep, dest_arr, last_seen) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
          + "ON CONFLICT (rid) DO UPDATE SET "
          + "  uid = EXCLUDED.uid, train_id = EXCLUDED.train_id, ssd = EXCLUDED.ssd, "
          + "  toc = EXCLUDED.toc, origin_tpl = EXCLUDED.origin_tpl, dest_tpl = EXCLUDED.dest_tpl, "
          + "  origin_dep = EXCLUDED.origin_dep, dest_arr = EXCLUDED.dest_arr, "
          + "  last_seen = now()";

  // SC may re-issue a rid with a changed calling-point list, so stops are delete-then-insert per
  // rid
  // (ON CONFLICT can't remove now-absent seqs).
  private static final String DELETE_STOPS_BY_RID_SQL =
      "DELETE FROM darwin_schedule_stops WHERE rid = ANY(?)";

  private static final int BATCH_SIZE = 2_000;

  private final DataSource dataSource;

  public PostgresTrainScheduleStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Returns the full schedule row for the given Darwin {@code rid}, or {@link Optional#empty()} if
   * not loaded. Used by the platform board to enrich occupants with origin/destination names.
   */
  public Optional<TrainSchedule> byRid(String rid) {
    String sql =
        "SELECT rid, uid, train_id, to_char(ssd, 'YYYY-MM-DD') AS ssd, toc, "
            + "origin_tpl, dest_tpl, origin_dep, dest_arr "
            + "FROM darwin_schedules WHERE rid = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, rid);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return Optional.of(
              new TrainSchedule(
                  rs.getString("rid"),
                  rs.getString("uid"),
                  rs.getString("train_id"),
                  rs.getString("ssd"),
                  rs.getString("toc"),
                  rs.getString("origin_tpl"),
                  rs.getString("dest_tpl"),
                  rs.getString("origin_dep"),
                  rs.getString("dest_arr")));
        }
        return Optional.empty();
      }
    } catch (SQLException e) {
      log.debug("byRid: failed to look up rid '{}': {}", rid, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Returns the 4-char signalling headcode ({@code train_id}) for the given Darwin {@code rid}, or
   * {@link Optional#empty()} if the schedule is not loaded. Used by the platform feed to key
   * updates by headcode for station-board lookups.
   */
  public Optional<String> headcodeForRid(String rid) {
    String sql = "SELECT train_id FROM darwin_schedules WHERE rid = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, rid);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return Optional.ofNullable(rs.getString("train_id"));
        }
        return Optional.empty();
      }
    } catch (SQLException e) {
      log.debug("headcodeForRid: failed to look up rid '{}': {}", rid, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * All public calling points for the given Darwin {@code rid}, ordered by sequence. Returns an
   * empty list if the rid is unknown or the table is not populated. Callers should treat this as a
   * graceful degradation — no exception is propagated.
   */
  public List<TrainCallingPoint> callingPoints(String rid) {
    String sql =
        "SELECT rid, seq, tpl, arr, dep FROM darwin_schedule_stops " + "WHERE rid = ? ORDER BY seq";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, rid);
      try (ResultSet rs = ps.executeQuery()) {
        List<TrainCallingPoint> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(
              new TrainCallingPoint(
                  rs.getString("rid"),
                  rs.getInt("seq"),
                  rs.getString("tpl"),
                  rs.getString("arr"),
                  rs.getString("dep")));
        }
        return rows;
      }
    } catch (SQLException e) {
      log.debug("callingPoints: failed to query stops for rid '{}': {}", rid, e.getMessage());
      return List.of();
    }
  }

  /**
   * Finds schedules whose calling points include the given TIPLOC at the given time on the given
   * date. The time match is a prefix on the stored {@code HH:MM[:SS]} string, so an {@code "HH:MM"}
   * query matches both forms. Pass {@code isArrival=true} to match the arrival column instead of
   * departure.
   *
   * <p>Returns an empty list on any failure — the caller should retry with an adjacent date before
   * giving up, and degrade gracefully if still empty.
   */
  public List<TrainSchedule> findCallingAt(
      String tpl, String hhmm, boolean isArrival, LocalDate ssd) {
    String timeCol = isArrival ? "cp.arr" : "cp.dep";
    String sql =
        "SELECT s.rid, s.uid, s.train_id, to_char(s.ssd, 'YYYY-MM-DD') AS ssd, s.toc, "
            + "s.origin_tpl, s.dest_tpl, s.origin_dep, s.dest_arr "
            + "FROM darwin_schedules s "
            + "JOIN darwin_schedule_stops cp ON cp.rid = s.rid "
            + "WHERE cp.tpl = ? AND "
            + timeCol
            + " LIKE ? AND s.ssd = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, tpl);
      ps.setString(2, hhmm + "%");
      ps.setObject(3, ssd);
      try (ResultSet rs = ps.executeQuery()) {
        List<TrainSchedule> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(
              new TrainSchedule(
                  rs.getString("rid"),
                  rs.getString("uid"),
                  rs.getString("train_id"),
                  rs.getString("ssd"),
                  rs.getString("toc"),
                  rs.getString("origin_tpl"),
                  rs.getString("dest_tpl"),
                  rs.getString("origin_dep"),
                  rs.getString("dest_arr")));
        }
        return rows;
      }
    } catch (SQLException e) {
      log.debug(
          "findCallingAt: failed for tpl='{}' time='{}' ssd={}: {}",
          tpl,
          hhmm,
          ssd,
          e.getMessage());
      return List.of();
    }
  }

  /**
   * Upserts a batch of live push-port SC schedules and their calling points in one transaction.
   * Stops are delete-then-insert per rid, so a re-issued schedule with a changed calling-point list
   * leaves no stale rows. Idempotent; call from the schedule-feed sweep thread only. Throws so the
   * caller can re-queue and retry on the next sweep.
   */
  private void writeLiveBatch(List<ScheduleRecord> records) throws SQLException {
    if (records.isEmpty()) {
      return;
    }
    String[] rids = records.stream().map(r -> r.header().rid()).toArray(String[]::new);
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try (PreparedStatement del = conn.prepareStatement(DELETE_STOPS_BY_RID_SQL);
          PreparedStatement ps = conn.prepareStatement(UPSERT_SQL);
          PreparedStatement sp = conn.prepareStatement(INSERT_STOP_SQL)) {
        del.setArray(1, conn.createArrayOf("text", rids));
        del.executeUpdate();
        int pending = 0;
        int stopPending = 0;
        for (ScheduleRecord r : records) {
          TrainSchedule h = r.header();
          ps.setString(1, h.rid());
          ps.setString(2, h.uid());
          ps.setString(3, h.trainId());
          ps.setObject(4, h.ssd().isEmpty() ? null : LocalDate.parse(h.ssd()));
          ps.setString(5, h.toc());
          ps.setString(6, h.originTpl());
          ps.setString(7, h.destTpl());
          ps.setString(8, h.originDep());
          ps.setString(9, h.destArr());
          ps.addBatch();
          if (++pending >= BATCH_SIZE) {
            ps.executeBatch();
            pending = 0;
          }
          for (TrainCallingPoint cp : r.callingPoints()) {
            sp.setString(1, cp.rid());
            sp.setInt(2, cp.seq());
            sp.setString(3, cp.tpl());
            sp.setString(4, cp.arr());
            sp.setString(5, cp.dep());
            sp.addBatch();
            if (++stopPending >= BATCH_SIZE) {
              sp.executeBatch();
              stopPending = 0;
            }
          }
        }
        if (pending > 0) {
          ps.executeBatch();
        }
        if (stopPending > 0) {
          sp.executeBatch();
        }
        conn.commit();
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      }
    }
  }

  /**
   * Deletes schedules (and their stops) with {@code ssd} before {@code cutoff}, bounding growth
   * from accumulated live SC rows. Best-effort: logs and returns rows deleted, never throws.
   */
  public long evictOldSchedules(LocalDate cutoff) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement delStops =
            conn.prepareStatement(
                "DELETE FROM darwin_schedule_stops WHERE rid IN "
                    + "(SELECT rid FROM darwin_schedules WHERE ssd < ?)");
        PreparedStatement delSched =
            conn.prepareStatement("DELETE FROM darwin_schedules WHERE ssd < ?")) {
      delStops.setObject(1, cutoff);
      delStops.executeUpdate();
      delSched.setObject(1, cutoff);
      return delSched.executeUpdate();
    } catch (SQLException e) {
      log.debug("evictOldSchedules: failed for cutoff {}: {}", cutoff, e.getMessage());
      return 0L;
    }
  }

  @Override
  public void upsertLiveBatch(List<ScheduleRecord> records) throws StorageException {
    try {
      writeLiveBatch(records);
    } catch (SQLException e) {
      throw new StorageException("Train schedule write failed", e);
    }
  }
}
