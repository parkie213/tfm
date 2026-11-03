package uk.org.tfm.server.lifecycle;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.server.config.Config;

/** Opens the listener last and stops accepting requests before background services are released. */
@Singleton
public final class GrpcLifecycle implements ManagedService {
  private static final Logger log = LoggerFactory.getLogger(GrpcLifecycle.class);
  private final Server server;
  private final HealthStatusManager health;
  private final Config config;

  @Inject
  public GrpcLifecycle(Server server, HealthStatusManager health, Config config) {
    this.server = server;
    this.health = health;
    this.config = config;
  }

  @Override
  public void start() throws IOException {
    server.start();
    health.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.SERVING);
    log.info("TFM gRPC server listening on port {}", config.grpcPort());
    if (config.darwinToken().isBlank()) {
      log.warn("DARWIN_TOKEN is not set — train departures will fail until it is configured.");
    }
  }

  @Override
  public void stop() throws InterruptedException {
    health.enterTerminalState();
    server.shutdown();
    try {
      if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      server.shutdownNow();
      Thread.currentThread().interrupt();
      throw e;
    }
  }
}
