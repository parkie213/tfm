package uk.org.tfm.model.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import uk.org.tfm.model.feed.FeedException;

/** Supplies a reference catalog snapshot; implementations may cache the source between fetches. */
public interface CatalogProvider<T> {
  List<T> fetch() throws FeedException;

  /** Timestamp of the cached source, not confirmation that persistence has accepted it. */
  Optional<Instant> lastLoadedAt();
}
