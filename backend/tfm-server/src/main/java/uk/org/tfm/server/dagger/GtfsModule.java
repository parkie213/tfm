package uk.org.tfm.server.dagger;

import dagger.Module;
import dagger.Provides;
import java.nio.file.Path;
import javax.inject.Singleton;
import uk.org.tfm.adapter.gtfs.GtfsClient;
import uk.org.tfm.model.timetable.TimetableProvider;
import uk.org.tfm.server.config.Config;

/** Supplies source timetables; application services own refresh, persistence, and geometry. */
@Module
public final class GtfsModule {
  @Provides
  @Singleton
  static TimetableProvider timetables(Config config) {
    return new GtfsClient(
        Path.of(config.gtfsCacheDir()), config.gtfsRegions(), config.gtfsLoadShapes());
  }
}
