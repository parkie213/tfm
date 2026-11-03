package uk.org.tfm.model.feed;

import java.time.Instant;
import java.util.Optional;

/** Read-only health of an on-demand feed. No successful call yet is distinct from a failed call. */
public interface FeedHealth {
  boolean configured();

  Optional<Instant> lastSuccessAt();

  Optional<Instant> lastErrorAt();

  Optional<String> lastError();
}
