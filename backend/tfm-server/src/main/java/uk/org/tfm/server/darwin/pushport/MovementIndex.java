package uk.org.tfm.server.darwin.pushport;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import uk.org.tfm.model.rail.MovementSnapshot;

/**
 * Thread-safe in-memory index of the merged {@link MovementSnapshot} per (headcode, tpl) pair — the
 * materialised movement state the expert review (D7) prescribed: <em>in-memory only</em> (the TS
 * firehose is never persisted at sweep rate) and <em>merged, not replaced</em>.
 *
 * <p>The key is {@code headcode + "|" + tpl}, mirroring {@link PlatformIndex} so the board can look
 * movement up by the same {@code (headcode, tpl)} they already hold, with no rid round-trip.
 * Headcode resolution from the incoming rid is {@link DarwinPlatformService}'s job (shared {@code
 * ridToHeadcode} cache); a movement whose rid is uncorrelated is dropped, exactly as an
 * uncorrelated platform update is.
 *
 * <p><strong>Merge semantics</strong> (the reason this is not just {@link PlatformIndex}): each
 * field is updated only when the incoming snapshot carries a value for it, so a later estimate-only
 * message ({@code et}) never clears a known actual ({@code at}). The {@code at} timestamp advances
 * to the newer of the two, driving TTL eviction. The lifecycle this preserves is {@code et-only →
 * arr.at + dep.et → arr.at + dep.at}.
 */
public final class MovementIndex {

  private final ConcurrentHashMap<String, MovementSnapshot> index = new ConcurrentHashMap<>();

  public MovementIndex() {}

  /** Merges {@code incoming} into the entry for {@code headcode} + {@code incoming.tpl()}. */
  public void merge(String headcode, MovementSnapshot incoming) {
    index.merge(key(headcode, incoming.tpl()), incoming, MovementIndex::mergeSnapshots);
  }

  /** The merged movement for a train at a location, or empty if none is held. */
  public Optional<MovementSnapshot> lookup(String headcode, String tpl) {
    return Optional.ofNullable(index.get(key(headcode, tpl)));
  }

  /**
   * Evicts entries whose {@link MovementSnapshot#at()} is older than {@code now - ttl}.
   *
   * @return the number of entries removed
   */
  public int sweep(Instant now, Duration ttl) {
    Instant cutoff = now.minus(ttl);
    int[] removed = {0};
    index
        .values()
        .removeIf(
            m -> {
              if (m.at().isBefore(cutoff)) {
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

  /**
   * Field-level merge: keep the incoming value where present, otherwise the existing one. The
   * incoming snapshot is the newer one (parse-time stamp), so its {@code at} wins.
   */
  private static MovementSnapshot mergeSnapshots(
      MovementSnapshot existing, MovementSnapshot incoming) {
    return new MovementSnapshot(
        incoming.rid(),
        incoming.tpl(),
        pick(incoming.arrAt(), existing.arrAt()),
        pick(incoming.arrEt(), existing.arrEt()),
        pick(incoming.depAt(), existing.depAt()),
        pick(incoming.depEt(), existing.depEt()),
        pick(incoming.passAt(), existing.passAt()),
        pick(incoming.passEt(), existing.passEt()),
        incoming.at().isAfter(existing.at()) ? incoming.at() : existing.at());
  }

  private static String pick(String incoming, String existing) {
    return incoming.isEmpty() ? existing : incoming;
  }

  private static String key(String headcode, String tpl) {
    return headcode + "|" + tpl;
  }
}
