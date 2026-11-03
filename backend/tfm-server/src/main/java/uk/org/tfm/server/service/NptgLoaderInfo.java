package uk.org.tfm.server.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Narrow read-only view of NPTG locality loader state needed by {@link DataSourceRegistry}.
 * Implemented by {@link uk.org.tfm.server.stops.NptgLocalityLoader}.
 */
public interface NptgLoaderInfo {
  Optional<Instant> lastLoadedAt();
}
