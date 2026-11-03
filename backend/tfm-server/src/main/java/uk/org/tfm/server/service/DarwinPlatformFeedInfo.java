package uk.org.tfm.server.service;

import java.time.Instant;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.server.darwin.pushport.DarwinFeedCoordinator;
import uk.org.tfm.server.darwin.pushport.PlatformIndex;

/**
 * Combines {@link DarwinFeedCoordinator} and {@link PlatformIndex} into the narrow {@link
 * PlatformFeedInfo} view consumed by {@link DataSourceRegistry}.
 */
@Singleton
public final class DarwinPlatformFeedInfo implements PlatformFeedInfo {

  private final DarwinFeedCoordinator service;
  private final PlatformIndex index;

  @Inject
  public DarwinPlatformFeedInfo(DarwinFeedCoordinator service, PlatformIndex index) {
    this.service = service;
    this.index = index;
  }

  @Override
  public boolean isEnabled() {
    return service.isEnabled();
  }

  @Override
  public int platformIndexSize() {
    return index.size();
  }

  @Override
  public boolean isRunning() {
    return service.isRunning();
  }

  @Override
  public Optional<Instant> lastMessageAt() {
    return service.lastMessageAt();
  }
}
