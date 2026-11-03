package uk.org.tfm.server;

import uk.org.tfm.server.dagger.DaggerAppComponent;

public final class Main {
  public static void main(String[] args) throws Exception {
    var component = DaggerAppComponent.create();
    var lifecycle = component.lifecycle();
    Runtime.getRuntime().addShutdownHook(new Thread(lifecycle::close, "tfm-shutdown"));
    try (lifecycle) {
      lifecycle.start();
      component.grpcServer().awaitTermination();
    }
  }

  private Main() {}
}
