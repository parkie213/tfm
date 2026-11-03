package uk.org.tfm.server.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Narrow read-only view of Darwin platform-feed state needed by {@link DataSourceRegistry}.
 * Implemented by {@link uk.org.tfm.server.darwin.pushport.DarwinPlatformService}.
 */
public interface PlatformFeedInfo {
  boolean isEnabled();

  int platformIndexSize();

  /** Whether the consumer has actually been started (enabled + start() ran). */
  boolean isRunning();

  /** When a message was last received from Kafka, or empty if none yet. */
  Optional<Instant> lastMessageAt();
}
