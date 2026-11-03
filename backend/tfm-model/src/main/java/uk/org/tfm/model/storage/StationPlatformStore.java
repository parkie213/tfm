package uk.org.tfm.model.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Persistent inventory of observed platform labels, keyed by source TIPLOC. */
public interface StationPlatformStore {
  Map<String, Set<String>> loadAll() throws StorageException;

  void upsertBatch(Map<String, Set<String>> observations, Instant observedAt)
      throws StorageException;
}
