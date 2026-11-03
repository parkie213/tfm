package uk.org.tfm.server.dagger;

import com.zaxxer.hikari.HikariDataSource;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntKey;
import dagger.multibindings.IntoMap;
import javax.inject.Provider;
import uk.org.tfm.server.bods.BusVehiclePoller;
import uk.org.tfm.server.darwin.pushport.DarwinFeedCoordinator;
import uk.org.tfm.server.gtfs.TimetableRefreshService;
import uk.org.tfm.server.lifecycle.GrpcLifecycle;
import uk.org.tfm.server.lifecycle.ManagedService;
import uk.org.tfm.server.service.BoardStreamService;
import uk.org.tfm.server.service.TrainBoardProbe;
import uk.org.tfm.server.stops.NaptanLoader;
import uk.org.tfm.server.stops.NptgLocalityLoader;

/** Explicit startup order; consumers stop before producers, with the database released last. */
@Module
public final class LifecycleModule {
  @Provides
  @IntoMap
  @IntKey(0)
  static ManagedService database(Provider<HikariDataSource> pool) {
    return new ManagedService() {
      private HikariDataSource opened;

      public void start() {
        opened = pool.get();
      } // Pool construction runs Flyway first.

      public void stop() {
        if (opened != null) opened.close();
      }
    };
  }

  @Provides
  @IntoMap
  @IntKey(10)
  static ManagedService stops(NaptanLoader service) {
    return ManagedService.of(service::start, service::stop);
  }

  @Provides
  @IntoMap
  @IntKey(20)
  static ManagedService localities(NptgLocalityLoader service) {
    return ManagedService.of(service::start, service::stop);
  }

  @Provides
  @IntoMap
  @IntKey(30)
  static ManagedService timetables(TimetableRefreshService service) {
    return ManagedService.of(service::start, service::stop);
  }

  @Provides
  @IntoMap
  @IntKey(40)
  static ManagedService buses(BusVehiclePoller service) {
    return ManagedService.of(service::start, service::stop);
  }

  @Provides
  @IntoMap
  @IntKey(50)
  static ManagedService trains(DarwinFeedCoordinator service) {
    return ManagedService.of(service::start, service::stop);
  }

  @Provides
  @IntoMap
  @IntKey(60)
  static ManagedService boards(BoardStreamService service) {
    return ManagedService.of(service::start, service::stop);
  }

  @Provides
  @IntoMap
  @IntKey(65)
  static ManagedService trainBoardProbe(TrainBoardProbe probe) {
    return ManagedService.of(probe::start, probe::stop);
  }

  @Provides
  @IntoMap
  @IntKey(70)
  static ManagedService grpc(GrpcLifecycle service) {
    return service;
  }
}
