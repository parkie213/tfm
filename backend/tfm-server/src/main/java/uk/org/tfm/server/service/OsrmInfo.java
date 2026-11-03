package uk.org.tfm.server.service;

/**
 * Narrow read-only view of OSRM state needed by {@link DataSourceRegistry}. Implemented by {@link
 * uk.org.tfm.server.db.OsrmRouter}.
 */
public interface OsrmInfo {
  boolean enabled();

  /**
   * Liveness probe: whether the OSRM server actually answers (transport-level). May block briefly.
   */
  boolean isReachable();
}
