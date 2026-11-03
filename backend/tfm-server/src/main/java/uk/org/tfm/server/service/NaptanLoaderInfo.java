package uk.org.tfm.server.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Narrow read-only view of NaPTAN loader state needed by {@link DataSourceRegistry}. Implemented by
 * {@link uk.org.tfm.server.stops.NaptanLoader}.
 */
public interface NaptanLoaderInfo {
  Optional<Instant> lastLoadedAt();
}
