package uk.org.tfm.server.service;

import java.util.List;

/**
 * Narrow read-only view of the GTFS loader state needed by {@link DataSourceRegistry}. Implemented
 * by {@link uk.org.tfm.server.gtfs.TimetableRefreshService}.
 */
public interface GtfsFeedInfo {
  boolean isEnabled();

  List<String> getRegions();
}
