package uk.org.tfm.adapter.darwin.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

/**
 * One-shot diagnostic: connects to the RDM Darwin Kafka feed, prints the raw body of the next few
 * messages to stdout, then exits. Used to pin down the actual on-the-wire message shape (the topic
 * is {@code …-JSON}, so the body is expected to be JSON, not the gzip-XML the Pport docs describe)
 * before finalising {@link PushportParser}. Reads the same {@code DARWIN_KAFKA_*} config as the
 * live feed; the consumer secret is never printed.
 *
 * <pre>java -cp app.jar uk.org.tfm.adapter.darwin.kafka.DarwinKafkaSampleCli [count]</pre>
 */
public final class DarwinKafkaSampleCli {

  private static final long DEADLINE_MS = 60_000;

  public static void main(String[] args) {
    DarwinKafkaSettings config =
        new DarwinKafkaSettings(
            env("DARWIN_KAFKA_BOOTSTRAP", ""),
            env("DARWIN_KAFKA_TOPIC", ""),
            env("DARWIN_KAFKA_KEY", ""),
            env("DARWIN_KAFKA_SECRET", ""),
            env("DARWIN_KAFKA_GROUP", "tfm-darwin-platform"),
            Boolean.parseBoolean(env("DARWIN_KAFKA_ENABLED", "true")));
    if (!config.enabled()) {
      System.err.println("DARWIN_KAFKA_{BOOTSTRAP,KEY,SECRET} not set — nothing to sample.");
      System.exit(1);
    }
    int want = args.length > 0 ? Integer.parseInt(args[0].trim()) : 1;

    Properties props = DarwinKafkaPlatformFeed.consumerProps(config);
    props.put("client.id", "tfm-darwin-sample");
    // A read-only probe: never advance the live feed's committed offsets.
    props.put("enable.auto.commit", "false");

    System.err.printf(
        "Sampling %d message(s) from %s (bootstrap=%s)…%n",
        want, config.topic(), config.bootstrap());

    int printed = 0;
    try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(props)) {
      // Manual partition assignment (not subscribe): this reads the partition INDEPENDENTLY of the
      // ACL consumer group, so it never triggers a rebalance with the live server's consumer — both
      // can read the same partition at once. seekToEnd → sample from the live tail, not history.
      List<TopicPartition> tps = new ArrayList<>();
      for (PartitionInfo pi : c.partitionsFor(config.topic())) {
        tps.add(new TopicPartition(pi.topic(), pi.partition()));
      }
      c.assign(tps);
      c.seekToEnd(tps);
      long deadline = System.currentTimeMillis() + DEADLINE_MS;
      while (printed < want && System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, byte[]> records = c.poll(Duration.ofSeconds(5));
        for (ConsumerRecord<String, byte[]> record : records) {
          byte[] v = record.value();
          System.out.printf(
              "---- message %d (key=%s, %d bytes) ----%n",
              printed + 1, record.key(), v == null ? 0 : v.length);
          System.out.println(v == null ? "<null>" : new String(v, StandardCharsets.UTF_8));
          if (++printed >= want) {
            break;
          }
        }
      }
    }
    if (printed == 0) {
      System.err.println(
          "No messages within 60s — check the topic/ACL, or the ~02:00 Darwin rebuild gap.");
      System.exit(2);
    }
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private DarwinKafkaSampleCli() {}
}
