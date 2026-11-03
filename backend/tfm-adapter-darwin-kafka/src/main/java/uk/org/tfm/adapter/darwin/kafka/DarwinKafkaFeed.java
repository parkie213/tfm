package uk.org.tfm.adapter.darwin.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.rail.MovementSnapshot;
import uk.org.tfm.model.rail.PlatformUpdate;
import uk.org.tfm.model.rail.TrainEventFeed;
import uk.org.tfm.model.rail.TrainEventSink;
import uk.org.tfm.model.rail.TrainScheduleDetails;

/**
 * {@link TrainEventFeed} backed by the Rail Data Marketplace (RDM) Darwin Real Time push port Kafka
 * feed. Consumes binary Pport messages from the configured topic and parses platform updates, train
 * schedules, and movement updates via {@link PushportParser}.
 *
 * <p>Resilient by design: a dropped connection or deserialization error triggers a capped
 * exponential-backoff reconnect on a single daemon thread, and the loop never dies on an unchecked
 * exception. The Kafka consumer secret is never logged. The feed is a no-op (logs a warning) when
 * the feed is disabled or credentials are absent.
 */
public final class DarwinKafkaFeed implements TrainEventFeed {

  private static final Logger log = LoggerFactory.getLogger(DarwinKafkaFeed.class);
  private static final long FIRST_BACKOFF_SECONDS = 5;
  private static final long MAX_BACKOFF_SECONDS = 120;
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);

  private final DarwinKafkaSettings config;
  private final java.util.function.Predicate<String> scope;
  private final boolean enabled;

  private volatile boolean running;
  private volatile Thread thread;
  private volatile Consumer<String, byte[]> consumer;
  private final Supplier<Consumer<String, byte[]>> consumerFactory;

  public DarwinKafkaFeed(DarwinKafkaSettings config, java.util.function.Predicate<String> scope) {
    this(config, scope, () -> new KafkaConsumer<>(consumerProps(config)));
  }

  DarwinKafkaFeed(
      DarwinKafkaSettings config,
      java.util.function.Predicate<String> scope,
      Supplier<Consumer<String, byte[]>> consumerFactory) {
    this.consumerFactory = consumerFactory;
    this.config = config;
    this.scope = scope;
    this.enabled = config.enabled();
  }

  @Override
  public synchronized void start(TrainEventSink sink) {
    if (!enabled) {
      log.warn("Darwin Kafka credentials not set — live platform numbers are disabled.");
      return;
    }
    if (running) {
      return;
    }
    running = true;
    Thread t = new Thread(() -> runLoop(sink), "darwin-kafka-platform-feed");
    t.setDaemon(true);
    thread = t;
    t.start();
    log.info(
        "Darwin platform feed starting (bootstrap={}, topic={}).",
        config.bootstrap(),
        config.topic());
  }

  @Override
  public synchronized void stop() {
    running = false;
    Consumer<String, byte[]> c = consumer;
    if (c != null) {
      c.wakeup(); // unblocks an in-progress poll() safely
    }
    Thread t = thread;
    if (t != null) {
      t.interrupt();
      try {
        t.join(5_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Connect → poll → reconnect, forever, until {@link #stop()}. Never throws. */
  private void runLoop(TrainEventSink sink) {
    int failures = 0;
    while (running) {
      try {
        connectAndConsume(sink);
        failures = 0;
      } catch (WakeupException e) {
        if (!running) {
          break; // stop() was called — expected shutdown path
        }
        // Unexpected wakeup (shouldn't happen without stop() — treat as transient).
        failures++;
        long delay = backoff(failures);
        log.warn("Darwin Kafka feed woken unexpectedly; reconnecting in {}s.", delay);
        if (!sleep(delay * 1_000)) {
          break;
        }
      } catch (Exception e) {
        if (!running) {
          break;
        }
        failures++;
        long delay = backoff(failures);
        log.warn(
            "Darwin Kafka feed connection lost ({}: {}); reconnecting in {}s.",
            e.getClass().getSimpleName(),
            e.getMessage(),
            delay);
        if (!sleep(delay * 1_000)) {
          break;
        }
      } finally {
        closeQuietly();
      }
    }
    log.info("Darwin platform feed stopped.");
  }

  /**
   * Builds the Confluent Cloud / RDM consumer properties (SASL_SSL + PLAIN). Shared with {@link
   * DarwinKafkaSampleCli}. The consumer secret lives only in this in-memory object and is never
   * logged. {@code group.id} must be the exact RDM-issued group — the API key is ACL-scoped to it.
   * Callers may override {@code client.id} / {@code enable.auto.commit} as needed.
   */
  static Properties consumerProps(DarwinKafkaSettings config) {
    Properties props = new Properties();
    props.put("bootstrap.servers", config.bootstrap());
    props.put("security.protocol", "SASL_SSL");
    props.put("sasl.mechanism", "PLAIN");
    props.put(
        "sasl.jaas.config",
        "org.apache.kafka.common.security.plain.PlainLoginModule required"
            + " username=\""
            + config.key()
            + "\""
            + " password=\""
            + config.secret()
            + "\";");
    props.put("group.id", config.group());
    props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    props.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    props.put("auto.offset.reset", "latest");
    props.put("enable.auto.commit", "true");
    props.put(
        "client.id",
        "tfm-darwin-platform-feed"); // distinguishes the prod consumer in Confluent metrics
    return props;
  }

  private void connectAndConsume(TrainEventSink sink) {
    Consumer<String, byte[]> c = consumerFactory.get();
    consumer = c;
    c.subscribe(List.of(config.topic()));
    log.info(
        "Darwin Kafka consumer subscribed (group={}, topic={}).", config.group(), config.topic());

    while (running) {
      ConsumerRecords<String, byte[]> records = c.poll(POLL_TIMEOUT);
      for (ConsumerRecord<String, byte[]> record : records) {
        dispatch(record.value(), sink);
      }
    }
  }

  void dispatch(byte[] raw, TrainEventSink sink) {
    PushportMessage msg = PushportParser.parse(raw, scope);
    for (PlatformUpdate update : msg.platformUpdates()) {
      try {
        sink.onPlatformUpdate(update);
      } catch (RuntimeException e) {
        log.warn("Platform sink rejected update for train {} at {}", update.rid(), update.tpl(), e);
      }
    }
    for (TrainScheduleDetails record : msg.schedules()) {
      try {
        sink.onScheduleDetails(record);
      } catch (RuntimeException e) {
        log.warn("Schedule sink rejected record for rid {}", record.header().rid(), e);
      }
    }
    for (MovementSnapshot movement : msg.movements()) {
      try {
        sink.onMovement(movement);
      } catch (RuntimeException e) {
        log.warn(
            "Movement sink rejected snapshot for rid {} at {}", movement.rid(), movement.tpl(), e);
      }
    }
  }

  private void closeQuietly() {
    Consumer<String, byte[]> c = consumer;
    consumer = null;
    if (c != null) {
      try {
        c.close();
      } catch (Exception ignored) {
        // closing a broken consumer — nothing to do
      }
    }
  }

  private static long backoff(int failures) {
    long delay = (long) (FIRST_BACKOFF_SECONDS * Math.pow(2, Math.max(0, failures - 1)));
    return Math.min(delay, MAX_BACKOFF_SECONDS);
  }

  /** Sleeps; returns false if interrupted (shutdown signal). */
  private static boolean sleep(long ms) {
    try {
      Thread.sleep(ms);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
