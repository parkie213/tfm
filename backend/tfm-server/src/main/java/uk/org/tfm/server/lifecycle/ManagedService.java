package uk.org.tfm.server.lifecycle;

/** A resource owned by the application. Stop must tolerate an incomplete start. */
public interface ManagedService {
  void start() throws Exception;

  void stop() throws Exception;

  static ManagedService of(Runnable start, Runnable stop) {
    return new ManagedService() {
      public void start() {
        start.run();
      }

      public void stop() {
        stop.run();
      }
    };
  }
}
