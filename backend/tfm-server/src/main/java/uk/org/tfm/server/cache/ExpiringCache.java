package uk.org.tfm.server.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Tiny time-bounded cache. Keeps upstream calls (e.g. Darwin) infrequent while the frontend polls
 * every ~25s. A brief stampede on expiry is acceptable at this traffic level.
 */
public final class ExpiringCache<K, V> {

  private record Entry<V>(V value, Instant expiresAt) {}

  private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
  private final Duration ttl;

  public ExpiringCache(Duration ttl) {
    this.ttl = ttl;
  }

  /** Returns a fresh-enough cached value, or loads, stores, and returns a new one. */
  public V get(K key, Function<K, V> loader) {
    Instant now = Instant.now();
    Entry<V> existing = entries.get(key);
    if (existing != null && existing.expiresAt().isAfter(now)) {
      return existing.value();
    }
    V value = loader.apply(key);
    entries.put(key, new Entry<>(value, now.plus(ttl)));
    return value;
  }
}
