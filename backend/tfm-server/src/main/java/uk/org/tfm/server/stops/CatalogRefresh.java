package uk.org.tfm.server.stops;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.catalog.CatalogProvider;

/** Shared application lifecycle for reference catalogs: immediate import, then daily refresh. */
final class CatalogRefresh<T> {
  private static final Logger log = LoggerFactory.getLogger(CatalogRefresh.class);

  @FunctionalInterface
  interface Sink<T> {
    void replace(List<T> rows) throws Exception;
  }

  private final String name;
  private final CatalogProvider<T> source;
  private final Sink<T> sink;
  private final ScheduledExecutorService scheduler;
  private volatile boolean running;

  CatalogRefresh(String name, CatalogProvider<T> source, Sink<T> sink) {
    this.name = name;
    this.source = source;
    this.sink = sink;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, name);
              thread.setDaemon(true);
              return thread;
            });
  }

  Optional<Instant> lastLoadedAt() {
    return source.lastLoadedAt();
  }

  synchronized void start() {
    if (running) return;
    running = true;
    scheduler.schedule(this::load, 0, TimeUnit.SECONDS);
    log.info("{} started; will reload every 24h.", name);
  }

  synchronized void stop() {
    running = false;
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  void load() {
    try {
      List<T> rows = source.fetch();
      sink.replace(rows);
      log.info("{} imported {} entries.", name, rows.size());
    } catch (Throwable e) {
      log.error("{} failed; keeping the previous catalog.", name, e);
    } finally {
      if (running) {
        try {
          scheduler.schedule(this::load, 24, TimeUnit.HOURS);
        } catch (RejectedExecutionException ignored) {
          // Application shutting down.
        }
      }
    }
  }
}
