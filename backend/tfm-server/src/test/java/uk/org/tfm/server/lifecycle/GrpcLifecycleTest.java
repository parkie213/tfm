package uk.org.tfm.server.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Server;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import uk.org.tfm.server.config.Config;

class GrpcLifecycleTest {
  private static class FakeServer extends Server {
    final List<String> events = new ArrayList<>();
    boolean graceful;
    boolean failStart;

    public Server start() throws IOException {
      events.add("start");
      if (failStart) throw new IOException("bind failed");
      return this;
    }

    public Server shutdown() {
      events.add("shutdown");
      return this;
    }

    public Server shutdownNow() {
      events.add("force");
      return this;
    }

    public boolean isShutdown() {
      return events.contains("shutdown");
    }

    public boolean isTerminated() {
      return graceful || events.contains("force");
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) {
      events.add("await " + timeout);
      return isTerminated();
    }

    public void awaitTermination() {}
  }

  @Test
  void forcesShutdownAfterGracePeriod() throws Exception {
    var server = new FakeServer();
    var service = new GrpcLifecycle(server, new HealthStatusManager(), Config.minimalForTesting());
    service.start();
    service.stop();
    assertEquals(List.of("start", "shutdown", "await 10", "force", "await 5"), server.events);
  }

  @Test
  void gracefulShutdownDoesNotForce() throws Exception {
    var server = new FakeServer();
    server.graceful = true;
    var service = new GrpcLifecycle(server, new HealthStatusManager(), Config.minimalForTesting());
    service.start();
    service.stop();
    assertEquals(List.of("start", "shutdown", "await 10"), server.events);
  }

  @Test
  void bindFailureStillReleasesServerThroughApplicationRollback() {
    var server = new FakeServer();
    server.failStart = true;
    server.graceful = true;
    var service = new GrpcLifecycle(server, new HealthStatusManager(), Config.minimalForTesting());
    var lifecycle = new ApplicationLifecycle(Map.of(0, () -> service));
    assertThrows(IOException.class, lifecycle::start);
    assertEquals(List.of("start", "shutdown", "await 10"), server.events);
  }
}
