package uk.org.tfm.server.dagger;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import uk.org.tfm.adapter.bods.SiriVmClient;
import uk.org.tfm.model.vehicle.VehiclePositionProvider;
import uk.org.tfm.server.config.Config;

@Module
public final class BodsModule {

  @Provides
  @Singleton
  static VehiclePositionProvider provideVehiclePositionProvider(Config config) {
    return new SiriVmClient(config.bodsApiKey(), config.bodsBbox());
  }
}
