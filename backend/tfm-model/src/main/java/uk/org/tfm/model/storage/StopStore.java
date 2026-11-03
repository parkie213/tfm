package uk.org.tfm.model.storage;

import java.util.List;
import uk.org.tfm.model.catalog.RankedStop;
import uk.org.tfm.model.catalog.StopDefinition;
import uk.org.tfm.model.catalog.StopPage;

/** Stores the stop catalog and supplies bounded spatial/search results. */
public interface StopStore extends StopCountSource {
  int SEARCH_DEFAULT_LIMIT = 15;
  int SEARCH_MAX_LIMIT = 50;

  StopPage inViewport(double minLat, double minLng, double maxLat, double maxLng)
      throws StorageException;

  List<RankedStop> search(String query, int limit) throws StorageException;

  void bulkLoad(List<StopDefinition> rows) throws StorageException;
}
