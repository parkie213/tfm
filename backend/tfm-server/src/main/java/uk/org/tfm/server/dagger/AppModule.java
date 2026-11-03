package uk.org.tfm.server.dagger;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import uk.org.tfm.server.config.Config;

@Module
public final class AppModule {

  @Provides
  @Singleton
  static Config provideConfig() {
    return Config.fromEnv();
  }
}
