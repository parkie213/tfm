package uk.org.tfm.server.stops;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.catalog.CatalogProvider;
import uk.org.tfm.model.feed.FeedException;

class CatalogRefreshTest {
  @Test
  void sourceFailureDoesNotReplacePreviouslyImportedCatalogAndLaterLoadRecovers() {
    var stored = new AtomicReference<>(List.of("previous"));
    var source =
        new CatalogProvider<String>() {
          int calls;

          public List<String> fetch() throws FeedException {
            if (calls++ == 0) {
              throw new FeedException(
                  "bad catalog", FeedException.Kind.INVALID_RESPONSE, Optional.empty());
            }
            return List.of("replacement");
          }

          public Optional<Instant> lastLoadedAt() {
            return Optional.empty();
          }
        };
    var refresh = new CatalogRefresh<>("test", source, stored::set);
    try {
      refresh.load();
      assertEquals(List.of("previous"), stored.get());
      refresh.load();
      assertEquals(List.of("replacement"), stored.get());
    } finally {
      refresh.stop();
    }
  }

  @Test
  void persistenceFailureDoesNotEscapeRefreshAndNextLoadCanProceed() {
    var stored = new AtomicReference<>(List.of("previous"));
    var source =
        new CatalogProvider<String>() {
          public List<String> fetch() {
            return List.of("replacement");
          }

          public Optional<Instant> lastLoadedAt() {
            return Optional.of(Instant.EPOCH);
          }
        };
    var refresh =
        new CatalogRefresh<>(
            "test",
            source,
            new CatalogRefresh.Sink<String>() {
              int calls;

              public void replace(List<String> rows) throws Exception {
                if (calls++ == 0) throw new Exception("database unavailable");
                stored.set(rows);
              }
            });
    try {
      assertDoesNotThrow(refresh::load);
      assertEquals(List.of("previous"), stored.get());
      refresh.load();
      assertEquals(List.of("replacement"), stored.get());
      assertEquals(Optional.of(Instant.EPOCH), refresh.lastLoadedAt());
    } finally {
      refresh.stop();
    }
  }
}
