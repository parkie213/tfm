package uk.org.tfm.server.dagger;

import dagger.Module;
import dagger.Provides;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import javax.inject.Singleton;
import uk.org.tfm.server.config.Config;
import uk.org.tfm.server.service.TfmServiceImpl;

@Module
public final class GrpcModule {

  /**
   * Standard gRPC health service. {@code GrpcLifecycle} flips it to SERVING only after the server
   * is up — which is after Flyway migrations are forced — so a container healthcheck / deploy
   * readiness gate that probes it means "migrated and serving", not merely "process started".
   */
  @Provides
  @Singleton
  static HealthStatusManager provideHealthStatusManager() {
    var health = new HealthStatusManager();
    health.setStatus(
        HealthStatusManager.SERVICE_NAME_ALL_SERVICES,
        HealthCheckResponse.ServingStatus.NOT_SERVING);
    return health;
  }

  @Provides
  @Singleton
  static Server provideServer(Config config, TfmServiceImpl service, HealthStatusManager health) {
    return ServerBuilder.forPort(config.grpcPort())
        .addService(service)
        .addService(health.getHealthService())
        // Server reflection lets grpcurl / Connect-ES probe the API without local proto files.
        .addService(ProtoReflectionServiceV1.newInstance())
        .build();
  }
}
