package uk.org.tfm.server.stops;

import java.time.Instant;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.catalog.CatalogProvider;
import uk.org.tfm.model.catalog.StopDefinition;
import uk.org.tfm.model.storage.StopStore;
import uk.org.tfm.server.service.NaptanLoaderInfo;

/** Refreshes the reference catalog into storage; source-specific work belongs to the adapter. */
@Singleton
public final class NaptanLoader implements NaptanLoaderInfo {
  private final CatalogRefresh<StopDefinition> refresh;

  @Inject
  public NaptanLoader(CatalogProvider<StopDefinition> source, StopStore stopDao) {
    refresh = new CatalogRefresh<>("NaptanLoader", source, stopDao::bulkLoad);
  }

  public Optional<Instant> lastLoadedAt() {
    return refresh.lastLoadedAt();
  }

  public void start() {
    refresh.start();
  }

  public void stop() {
    refresh.stop();
  }
}
