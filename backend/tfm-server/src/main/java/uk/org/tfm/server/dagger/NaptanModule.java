package uk.org.tfm.server.dagger;

import dagger.Module;
import dagger.Provides;
import java.nio.file.Path;
import javax.inject.Singleton;
import uk.org.tfm.adapter.stops.NaptanClient;
import uk.org.tfm.adapter.stops.NptgClient;
import uk.org.tfm.model.catalog.CatalogProvider;
import uk.org.tfm.model.catalog.LocalityDefinition;
import uk.org.tfm.model.catalog.StopDefinition;
import uk.org.tfm.server.config.Config;

/** Wires catalog providers; scheduling and database imports stay in application services. */
@Module
public final class NaptanModule {
  @Provides
  @Singleton
  static CatalogProvider<StopDefinition> stops(Config config) {
    return new NaptanClient(Path.of(config.gtfsCacheDir()), config.naptanAreas());
  }

  @Provides
  @Singleton
  static CatalogProvider<LocalityDefinition> localities(Config config) {
    return new NptgClient(Path.of(config.gtfsCacheDir()), config.nptgLocalitiesUrl());
  }
}
