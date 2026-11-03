package uk.org.tfm.server.service;

import java.time.Instant;
import uk.org.tfm.model.storage.StorageException;

/**
 * Narrow read-only view of GTFS load state needed by {@link DataSourceRegistry}. Implemented by
 * {@link GtfsLoadStateInfo}.
 */
public interface LoadStateInfo {
  record FeedState(Instant feedLoadedAt, String feedVersion) {}

  /** Returns the current GTFS feed state; fields may be null if nothing is loaded. */
  FeedState read() throws StorageException;
}
