package uk.org.tfm.model.storage;

/** A persistence failure without a database-driver dependency in application contracts. */
public final class StorageException extends Exception {
  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
