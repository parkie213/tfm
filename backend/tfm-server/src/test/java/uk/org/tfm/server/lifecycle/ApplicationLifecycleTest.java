package uk.org.tfm.server.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Provider;
import org.junit.jupiter.api.Test;

class ApplicationLifecycleTest {
  private Provider<ManagedService> service(List<String> events, String name) {
    return () -> {
      events.add("construct " + name);
      return ManagedService.of(() -> events.add("start " + name), () -> events.add("stop " + name));
    };
  }

  @Test
  void createsAndStartsInKeyOrderThenStopsInReverseOnce() throws Exception {
    var events = new ArrayList<String>();
    var lifecycle =
        new ApplicationLifecycle(
            Map.of(20, service(events, "server"), 0, service(events, "database")));
    assertTrue(events.isEmpty());
    lifecycle.start();
    lifecycle.start();
    lifecycle.close();
    lifecycle.close();
    assertEquals(
        List.of(
            "construct database",
            "start database",
            "construct server",
            "start server",
            "stop server",
            "stop database"),
        events);
    assertThrows(IllegalStateException.class, lifecycle::start);
  }

  @Test
  void failedStartCleansPartlyStartedServiceAndDoesNotConstructLaterServices() {
    var events = new ArrayList<String>();
    var failure = new IllegalStateException("startup failed");
    var lifecycle =
        new ApplicationLifecycle(
            Map.of(
                0, service(events, "database"),
                10,
                    () ->
                        ManagedService.of(
                            () -> {
                              events.add("partial start");
                              throw failure;
                            },
                            () -> events.add("partial stop")),
                20, service(events, "server")));
    assertSame(failure, assertThrows(IllegalStateException.class, lifecycle::start));
    lifecycle.close();
    assertEquals(
        List.of(
            "construct database",
            "start database",
            "partial start",
            "partial stop",
            "stop database"),
        events);
  }

  @Test
  void constructionFailureReleasesEarlierServices() {
    var events = new ArrayList<String>();
    var lifecycle =
        new ApplicationLifecycle(
            Map.of(
                0,
                service(events, "database"),
                10,
                () -> {
                  throw new IllegalArgumentException("construction failed");
                }));
    assertThrows(IllegalArgumentException.class, lifecycle::start);
    assertEquals(List.of("construct database", "start database", "stop database"), events);
  }

  @Test
  void shutdownFailureDoesNotPreventDatabaseCleanup() throws Exception {
    var events = new ArrayList<String>();
    var lifecycle =
        new ApplicationLifecycle(
            Map.of(
                0,
                service(events, "database"),
                10,
                () ->
                    ManagedService.of(
                        () -> {},
                        () -> {
                          throw new IllegalStateException("stop failed");
                        })));
    lifecycle.start();
    assertDoesNotThrow(lifecycle::close);
    assertEquals(List.of("construct database", "start database", "stop database"), events);
  }

  @Test
  void preservesStartupFailureAndAttachesCleanupFailure() {
    var startFailure = new IllegalStateException("start");
    var stopFailure = new IllegalStateException("stop");
    var lifecycle =
        new ApplicationLifecycle(
            Map.of(
                0,
                () ->
                    ManagedService.of(
                        () -> {
                          throw startFailure;
                        },
                        () -> {
                          throw stopFailure;
                        })));
    assertSame(startFailure, assertThrows(IllegalStateException.class, lifecycle::start));
    assertArrayEquals(new Throwable[] {stopFailure}, startFailure.getSuppressed());
  }
}
