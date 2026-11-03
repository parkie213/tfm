package uk.org.tfm.model.storage;

/** Read-only catalog count, independent of the storage implementation. */
public interface LocalityCountSource {
  long localityCount() throws StorageException;
}
