package uk.org.tfm.server.service;

import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.storage.LoadState;
import uk.org.tfm.model.storage.LoadStateStore;
import uk.org.tfm.model.storage.StorageException;

/** Wraps {@link LoadStateStore} as a {@link LoadStateInfo} for {@link DataSourceRegistry}. */
@Singleton
public final class GtfsLoadStateInfo implements LoadStateInfo {

  private final LoadStateStore dao;

  @Inject
  public GtfsLoadStateInfo(LoadStateStore dao) {
    this.dao = dao;
  }

  @Override
  public FeedState read() throws StorageException {
    LoadState ls = dao.read();
    return new FeedState(ls.feedLoadedAt(), ls.feedVersion());
  }
}
