package uk.org.tfm.server.darwin.pushport;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import uk.org.tfm.model.rail.MovementSnapshot;

/** Merged movement per dated Darwin RID and TIPLOC. Headcodes cannot identify a dated run. */
public final class MovementIndex {

  private final ConcurrentHashMap<String, MovementSnapshot> index = new ConcurrentHashMap<>();

  public MovementIndex() {}

  /** Merges {@code incoming} into the entry for {@code rid} + {@code incoming.tpl()}. */
  public void merge(String rid, MovementSnapshot incoming) {
    index.merge(key(rid, incoming.tpl()), incoming, MovementIndex::mergeSnapshots);
  }

  /** The merged movement for a train at a location, or empty if none is held. */
  public Optional<MovementSnapshot> lookup(String rid, String tpl) {
    return Optional.ofNullable(index.get(key(rid, tpl)));
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

  private static String key(String rid, String tpl) {
    return rid + "|" + tpl;
  }
}
