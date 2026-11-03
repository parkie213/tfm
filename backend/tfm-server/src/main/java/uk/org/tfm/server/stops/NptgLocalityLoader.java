package uk.org.tfm.server.stops;

import java.time.Instant;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.catalog.CatalogProvider;
import uk.org.tfm.model.catalog.LocalityDefinition;
import uk.org.tfm.model.storage.LocalityStore;
import uk.org.tfm.server.service.NptgLoaderInfo;

/** Refreshes the reference catalog into storage; source-specific work belongs to the adapter. */
@Singleton
public final class NptgLocalityLoader implements NptgLoaderInfo {
  private final CatalogRefresh<LocalityDefinition> refresh;

  @Inject
  public NptgLocalityLoader(CatalogProvider<LocalityDefinition> source, LocalityStore localityDao) {
    refresh = new CatalogRefresh<>("NptgLocalityLoader", source, localityDao::bulkLoad);
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
