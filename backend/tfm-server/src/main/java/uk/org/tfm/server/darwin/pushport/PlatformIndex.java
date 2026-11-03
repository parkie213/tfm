package uk.org.tfm.server.darwin.pushport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.rail.PlatformUpdate;

/**
 * Thread-safe in-memory index of the most recent {@link PlatformUpdate} per (headcode, tpl) pair.
 *
 * <p>The index key is {@code headcode + "|" + tpl} where {@code headcode} is the 4-char TRUST
 * signalling ID (Darwin {@code train_id}) resolved from the incoming {@code rid} by {@link
 * DarwinPlatformService}, allowing station boards to correlate platform and movement state.
 *
 * <p>Updates are accepted on a newest-{@link PlatformUpdate#at()}-wins basis: a later-timestamped
 * update always replaces an earlier one, and an earlier one is silently dropped. Stale entries are
 * removed by the {@link #sweep} method, which the {@link DarwinPlatformService} calls on a
 * background schedule.
 *
 * <p>The stored value is a {@link Stored} record that pairs the headcode with the update so that
 * callers such as the station board can recover the headcode for display purposes.
 */
@Singleton
public final class PlatformIndex {

  /**
   * The value held in the index: the resolved headcode paired with the platform update. Exposed so
   * the station-board builder can recover the headcode (which is the index key, not part of the
   * update itself) without a second lookup.
   */
  public record Stored(String headcode, PlatformUpdate update) {}

  private final ConcurrentHashMap<String, Stored> index = new ConcurrentHashMap<>();

  @Inject
  public PlatformIndex() {}

  /**
   * Stores {@code update} under the given {@code headcode} + {@code update.tpl()} key, if no entry
   * exists for that pair or if the existing entry is older (by {@link PlatformUpdate#at()}) than
   * the incoming one.
   *
   * @param headcode the 4-char TRUST signalling ID resolved from the update's rid
   * @param update the platform update to store
   */
  public void put(String headcode, PlatformUpdate update) {
    String key = key(headcode, update.tpl());
    Stored incoming = new Stored(headcode, update);
    index.merge(
        key,
        incoming,
        (existing, candidate) ->
            candidate.update().at().isAfter(existing.update().at()) ? candidate : existing);
  }

  /**
   * Returns the most recent {@link PlatformUpdate} for the given train headcode and location, or
   * {@link Optional#empty()} if none is held.
   *
   * @param headcode the 4-char TRUST signalling ID
   * @param tpl the TIPLOC of the location
   */
  public Optional<PlatformUpdate> lookup(String headcode, String tpl) {
    Stored stored = index.get(key(headcode, tpl));
    return stored == null ? Optional.empty() : Optional.of(stored.update());
  }

  /**
   * Returns all current {@link Stored} entries whose {@link PlatformUpdate#tpl()} equals the given
   * TIPLOC (case-insensitive), excluding suppressed entries. Used by the station board to enumerate
   * all trains currently assigned to a platform at a given station.
   *
   * @param tpl the TIPLOC of the station
   * @return a snapshot list; safe to iterate after the call returns
   */
  public List<Stored> forTpl(String tpl) {
    if (tpl == null || tpl.isBlank()) {
      return List.of();
    }
    String upperTpl = tpl.toUpperCase();
    List<Stored> result = new ArrayList<>();
    for (Stored s : index.values()) {
      if (s.update().tpl().toUpperCase().equals(upperTpl) && !s.update().suppressed()) {
        result.add(s);
      }
    }
    return result;
  }

  /**
   * Evicts entries whose {@link PlatformUpdate#at()} is older than {@code now - ttl}.
   *
   * @return the number of entries removed
   */
  public int sweep(Instant now, Duration ttl) {
    Instant cutoff = now.minus(ttl);
    int[] removed = {0};
    index
        .values()
        .removeIf(
            s -> {
              if (s.update().at().isBefore(cutoff)) {
                removed[0]++;
                return true;
              }
              return false;
            });
    return removed[0];
  }

  /** Current number of entries in the index. */
  public int size() {
    return index.size();
  }

  private static String key(String headcode, String tpl) {
    return headcode + "|" + tpl;
  }
}
