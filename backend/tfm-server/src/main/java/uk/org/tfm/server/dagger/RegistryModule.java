package uk.org.tfm.server.dagger;

import dagger.Binds;
import dagger.Module;
import javax.inject.Singleton;
import uk.org.tfm.server.bods.BusVehicleService;
import uk.org.tfm.server.db.OsrmRouter;
import uk.org.tfm.server.gtfs.TimetableRefreshService;
import uk.org.tfm.server.service.BusVehicleFeedStatus;
import uk.org.tfm.server.service.DarwinPlatformFeedInfo;
import uk.org.tfm.server.service.GtfsFeedInfo;
import uk.org.tfm.server.service.GtfsLoadStateInfo;
import uk.org.tfm.server.service.LoadStateInfo;
import uk.org.tfm.server.service.NaptanLoaderInfo;
import uk.org.tfm.server.service.NptgLoaderInfo;
import uk.org.tfm.server.service.OsrmInfo;
import uk.org.tfm.server.service.PlatformFeedInfo;
import uk.org.tfm.server.stops.NaptanLoader;
import uk.org.tfm.server.stops.NptgLocalityLoader;

/**
 * Binds the narrow service-info interfaces consumed by {@link
 * uk.org.tfm.server.service.DataSourceRegistry} to their singleton implementations.
 */
@Module
public abstract class RegistryModule {

  @Binds
  @Singleton
  abstract BusVehicleFeedStatus bindBusVehicleFeedStatus(BusVehicleService impl);

  @Binds
  @Singleton
  abstract PlatformFeedInfo bindPlatformFeedInfo(DarwinPlatformFeedInfo impl);

  @Binds
  @Singleton
  abstract GtfsFeedInfo bindGtfsFeedInfo(TimetableRefreshService impl);

  @Binds
  @Singleton
  abstract LoadStateInfo bindLoadStateInfo(GtfsLoadStateInfo impl);

  @Binds
  @Singleton
  abstract NaptanLoaderInfo bindNaptanLoaderInfo(NaptanLoader impl);

  @Binds
  @Singleton
  abstract NptgLoaderInfo bindNptgLoaderInfo(NptgLocalityLoader impl);

  @Binds
  @Singleton
  abstract OsrmInfo bindOsrmInfo(OsrmRouter impl);
}
