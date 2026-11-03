package uk.org.tfm.server.dagger;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import uk.org.tfm.adapter.darwin.DarwinClient;
import uk.org.tfm.model.board.BoardProvider;
import uk.org.tfm.model.feed.TrainBoardFeeds;
import uk.org.tfm.server.config.Config;

@Module
public final class DarwinModule {

  @Provides
  @Singleton
  static DarwinClient provideDarwinClient(Config config) {
    return new DarwinClient(
        config.darwinBaseUrl(),
        config.darwinToken(),
        config.darwinArrivalsBaseUrl(),
        config.darwinArrivalsToken(),
        config.darwinTimeoutMs());
  }

  @Provides
  @Singleton
  static BoardProvider provideBoardProvider(DarwinClient client) {
    return client;
  }

  /** Surfaces the DarwinClient's per-product health views for the data-sources page. */
  @Provides
  @Singleton
  static TrainBoardFeeds provideTrainBoardFeeds(DarwinClient client) {
    return new TrainBoardFeeds(client.departuresHealth(), client.arrivalsHealth());
  }
}
