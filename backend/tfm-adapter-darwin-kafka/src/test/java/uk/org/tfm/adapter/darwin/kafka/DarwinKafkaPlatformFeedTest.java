package uk.org.tfm.adapter.darwin.kafka;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.rail.MovementSnapshot;
import uk.org.tfm.model.rail.PlatformUpdate;
import uk.org.tfm.model.rail.ScheduleRecord;
import uk.org.tfm.model.rail.TrainEventSink;

class DarwinKafkaPlatformFeedTest {
  private static DarwinKafkaSettings settings(boolean enabled) {
    return new DarwinKafkaSettings(
        "broker:9092", "darwin", "test-key", "test-secret", "issued-group", enabled);
  }

  private static final byte[] MESSAGE =
      """
      {"uR":{"TS":{"rid":"train-1","Location":{"tpl":"NTNG","plat":{"":"2"},"arr":{"at":"10:00"}}},
      "schedule":{"rid":"train-1","trainId":"1F77","ssd":"2026-10-28",
      "OR":{"tpl":"NTNG","ptd":"10:01"},"DT":{"tpl":"LESTER","pta":"10:30"}}}}
      """
          .getBytes(StandardCharsets.UTF_8);

  @Test
  void propertiesPreserveIssuedGroupAuthenticationAndLiveOffsetPolicy() {
    var properties = DarwinKafkaPlatformFeed.consumerProps(settings(true));
    assertEquals("issued-group", properties.getProperty("group.id"));
    assertEquals("SASL_SSL", properties.getProperty("security.protocol"));
    assertEquals("PLAIN", properties.getProperty("sasl.mechanism"));
    assertEquals("latest", properties.getProperty("auto.offset.reset"));
    assertEquals("true", properties.getProperty("enable.auto.commit"));
    assertTrue(properties.getProperty("sasl.jaas.config").contains("username=\"test-key\""));
    assertTrue(properties.getProperty("sasl.jaas.config").contains("password=\"test-secret\""));
    assertFalse(settings(true).toString().contains("test-secret"));
    assertFalse(settings(true).toString().contains("test-key"));
  }

  @Test
  void disabledFeedDoesNotCreateConsumer() {
    var creations = new AtomicInteger();
    var feed =
        new DarwinKafkaPlatformFeed(
            settings(false),
            "NTNG"::equals,
            () -> {
              creations.incrementAndGet();
              throw new AssertionError("Disabled feed must not connect");
            });
    feed.start(new Sink());
    feed.start(new Sink());
    feed.stop();
    assertEquals(0, creations.get());
    assertFalse(new DarwinKafkaSettings("broker", "topic", "", "secret", "group", true).enabled());
  }

  @Test
  void rejectedPlatformEventDoesNotPreventScheduleAndMovementDelivery() {
    var schedules = new AtomicInteger();
    var movements = new AtomicInteger();
    var feed = new DarwinKafkaPlatformFeed(settings(true), "NTNG"::equals);
    feed.dispatch(
        MESSAGE,
        new Sink() {
          public void onPlatformUpdate(PlatformUpdate update) {
            throw new IllegalArgumentException("rejected");
          }

          public void onScheduleRecord(ScheduleRecord record) {
            schedules.incrementAndGet();
          }

          public void onMovement(MovementSnapshot movement) {
            movements.incrementAndGet();
          }
        });
    assertEquals(1, schedules.get());
    assertEquals(1, movements.get());
  }

  @Test
  void consumerSubscribesDeliversEventsAndClosesOnStop() throws Exception {
    var mock = new MockConsumer<String, byte[]>("earliest");
    var partition = new TopicPartition("darwin", 0);
    mock.schedulePollTask(
        () -> {
          mock.rebalance(List.of(partition));
          mock.updateBeginningOffsets(Map.of(partition, 0L));
          mock.updateEndOffsets(Map.of(partition, 1L));
          mock.addRecord(new ConsumerRecord<>("darwin", 0, 0, "key", MESSAGE));
        });
    var creations = new AtomicInteger();
    var delivered = new CountDownLatch(3);
    var feed =
        new DarwinKafkaPlatformFeed(
            settings(true),
            "NTNG"::equals,
            () -> {
              creations.incrementAndGet();
              return mock;
            });
    var sink =
        new Sink() {
          public void onPlatformUpdate(PlatformUpdate update) {
            delivered.countDown();
          }

          public void onScheduleRecord(ScheduleRecord record) {
            delivered.countDown();
          }

          public void onMovement(MovementSnapshot movement) {
            delivered.countDown();
          }
        };
    try {
      feed.start(sink);
      feed.start(sink);
      assertTrue(delivered.await(3, TimeUnit.SECONDS), "All three event kinds should arrive");
      assertEquals(1, creations.get());
      assertEquals(List.of("darwin"), mock.subscription().stream().toList());
    } finally {
      feed.stop();
      feed.stop();
    }
    assertTrue(mock.closed());
  }

  private static class Sink implements TrainEventSink {
    public void onPlatformUpdate(PlatformUpdate update) {}

    public void onScheduleRecord(ScheduleRecord record) {}

    public void onMovement(MovementSnapshot movement) {}
  }
}
