package uk.org.tfm.server.dagger;

import dagger.Component;
import io.grpc.Server;
import javax.inject.Singleton;
import uk.org.tfm.server.lifecycle.ApplicationLifecycle;

@Singleton
@Component(
    modules = {
      AppModule.class,
      DarwinModule.class,
      BodsModule.class,
      GrpcModule.class,
      GtfsModule.class,
      DbModule.class,
      NaptanModule.class,
      FeedModule.class,
      RegistryModule.class,
      LifecycleModule.class
    })
public interface AppComponent {
  ApplicationLifecycle lifecycle();

  Server grpcServer();
}
