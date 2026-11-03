package uk.org.tfm.server.dagger;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import uk.org.tfm.adapter.darwin.kafka.DarwinKafkaPlatformFeed;
import uk.org.tfm.adapter.darwin.kafka.DarwinKafkaSettings;
import uk.org.tfm.model.rail.TrainEventFeed;
import uk.org.tfm.server.config.Config;
import uk.org.tfm.server.darwin.pushport.PlatformScope;

/** Wires the optional Kafka transport to the train-event capability. */
@Module
public final class FeedModule {
  @Provides
  @Singleton
  static TrainEventFeed trainEvents(Config config, PlatformScope scope) {
    var settings =
        new DarwinKafkaSettings(
            config.darwinKafkaBootstrap(),
            config.darwinKafkaTopic(),
            config.darwinKafkaKey(),
            config.darwinKafkaSecret(),
            config.darwinKafkaGroup(),
            config.platformFeedEnabled());
    return new DarwinKafkaPlatformFeed(settings, scope::inScope);
  }
}
