package uk.org.tfm.server.dagger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import uk.org.tfm.model.storage.BusGeometryStore;
import uk.org.tfm.model.storage.LoadStateStore;
import uk.org.tfm.model.storage.LocalityCountSource;
import uk.org.tfm.model.storage.LocalityStore;
import uk.org.tfm.model.storage.StationPlatformStore;
import uk.org.tfm.model.storage.StopCountSource;
import uk.org.tfm.model.storage.StopStore;
import uk.org.tfm.model.storage.TimetableStore;
import uk.org.tfm.model.storage.TrainScheduleStore;
import uk.org.tfm.model.storage.TripCountSource;
import uk.org.tfm.server.config.Config;
import uk.org.tfm.storage.postgres.PostgresBusGeometryStore;
import uk.org.tfm.storage.postgres.PostgresLoadStateStore;
import uk.org.tfm.storage.postgres.PostgresLocalityStore;
import uk.org.tfm.storage.postgres.PostgresStationPlatformStore;
import uk.org.tfm.storage.postgres.PostgresStopStore;
import uk.org.tfm.storage.postgres.PostgresTimetableStore;
import uk.org.tfm.storage.postgres.PostgresTrainScheduleStore;

@Module
public final class DbModule {

  /**
   * Builds the HikariCP pool and runs Flyway migrations before anyone queries. Pool is small on
   * purpose — these queries are I/O-bound, so more connections than ~(2*vCPU)+1 just add
   * contention.
   */
  @Provides
  @Singleton
  static HikariDataSource providePool(Config config) {
    HikariConfig hc = new HikariConfig();
    hc.setPoolName("tfm-pg");
    hc.setJdbcUrl(config.dbUrl());
    hc.setUsername(config.dbUser());
    hc.setPassword(config.dbPassword());
    hc.setMaximumPoolSize(12);
    hc.setMinimumIdle(2);
    hc.setConnectionTimeout(5_000);
    hc.setConnectionInitSql("SET TIME ZONE 'Europe/London'");
    // Cache prepared statements server-/driver-side: our hot queries are fixed-text templates
    // (the bus matcher uses unnest of arrays, so its SQL doesn't vary with batch size).
    hc.addDataSourceProperty("cachePrepStmts", "true");
    hc.addDataSourceProperty("prepStmtCacheSize", "250");
    hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
    HikariDataSource ds = new HikariDataSource(hc);

    try {
      Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

    } catch (RuntimeException | Error failure) {
      ds.close();
      throw failure;
    }
    return ds;
  }

  @Provides
  static DataSource dataSource(HikariDataSource pool) {
    return pool;
  }

  @Provides
  @Singleton
  static BusGeometryStore busGeometry(DataSource ds) {
    return new PostgresBusGeometryStore(ds);
  }

  @Provides
  @Singleton
  static TimetableStore timetables(DataSource ds) {
    return new PostgresTimetableStore(ds);
  }

  @Provides
  @Singleton
  static LoadStateStore loadStates(DataSource ds) {
    return new PostgresLoadStateStore(ds);
  }

  @Provides
  static TripCountSource tripCounts(TimetableStore store) {
    return store;
  }

  @Provides
  @Singleton
  static StopStore stops(DataSource ds) {
    return new PostgresStopStore(ds);
  }

  @Provides
  @Singleton
  static LocalityStore localities(DataSource ds) {
    return new PostgresLocalityStore(ds);
  }

  @Provides
  static StopCountSource stopCounts(StopStore store) {
    return store;
  }

  @Provides
  static LocalityCountSource localityCounts(LocalityStore store) {
    return store;
  }

  @Provides
  @Singleton
  static TrainScheduleStore provideTrainScheduleStore(DataSource ds) {
    return new PostgresTrainScheduleStore(ds);
  }

  @Provides
  @Singleton
  static StationPlatformStore provideStationPlatformStore(DataSource ds) {
    return new PostgresStationPlatformStore(ds);
  }
}
