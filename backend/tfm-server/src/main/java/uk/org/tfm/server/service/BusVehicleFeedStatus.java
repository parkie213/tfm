package uk.org.tfm.server.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Narrow read-only view of the live bus-vehicle state needed by {@link DataSourceRegistry}.
 * Implemented by {@link uk.org.tfm.server.bods.BusVehicleService}.
 */
public interface BusVehicleFeedStatus {
  boolean isEnabled();

  Optional<Instant> lastFetchedAt();

  int vehicleCount();

  boolean isStale();
}
