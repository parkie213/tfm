package uk.org.tfm.server.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts lazy Dagger registrations in key order and releases them in reverse order. */
@Singleton
public final class ApplicationLifecycle implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ApplicationLifecycle.class);
  private final Map<Integer, Provider<ManagedService>> services;
  private final List<ManagedService> started = new ArrayList<>();
  private boolean running;
  private boolean closed;

  @Inject
  public ApplicationLifecycle(Map<Integer, Provider<ManagedService>> services) {
    this.services = new TreeMap<>(services);
  }

  public synchronized void start() throws Exception {
    if (closed) throw new IllegalStateException("Application lifecycle is closed");
    if (running) return;
    try {
      for (var entry : services.entrySet()) {
        log.info("Starting application service {}", entry.getKey());
        ManagedService service = entry.getValue().get();
        // A failing start can already own resources; include it in rollback.
        started.add(service);
        service.start();
      }
      running = true;
    } catch (Exception | Error failure) {
      closed = true;
      stopStarted(failure);
      throw failure;
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    stopStarted(null);
  }

  private void stopStarted(Throwable startupFailure) {
    running = false;
    for (int i = started.size() - 1; i >= 0; i--) {
      try {
        started.get(i).stop();
      } catch (Exception | Error failure) {
        log.warn("Application service shutdown failed", failure);
        if (startupFailure != null && startupFailure != failure)
          startupFailure.addSuppressed(failure);
      }
    }
    started.clear();
  }
}
