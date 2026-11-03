package uk.org.tfm.model.storage;

import java.time.Instant;

/** Import bookkeeping and atomic publication of a staged bus-geometry generation. */
public interface LoadStateStore {
  LoadState read() throws StorageException;

  void setFeed(String version, Instant loadedAt) throws StorageException;

  void publishGeometry(String key, Instant builtAt) throws StorageException;
}
