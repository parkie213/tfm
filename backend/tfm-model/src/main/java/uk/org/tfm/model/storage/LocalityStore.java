package uk.org.tfm.model.storage;

import java.util.List;
import uk.org.tfm.model.catalog.LocalityDefinition;
import uk.org.tfm.model.catalog.RankedPlace;

/** Stores the place catalog and supplies ranked place search results. */
public interface LocalityStore extends LocalityCountSource {
  List<RankedPlace> search(String query, int limit) throws StorageException;

  void bulkLoad(List<LocalityDefinition> rows) throws StorageException;
}
