package uk.org.tfm.adapter.darwin.kafka;

/** Constructor-supplied Kafka connection settings; diagnostic output omits credentials. */
public record DarwinKafkaSettings(
    String bootstrap, String topic, String key, String secret, String group, boolean enabled) {
  public DarwinKafkaSettings {
    enabled = enabled && !bootstrap.isBlank() && !key.isBlank() && !secret.isBlank();
  }

  @Override
  public String toString() {
    return "DarwinKafkaSettings[bootstrap="
        + bootstrap
        + ", topic="
        + topic
        + ", group="
        + group
        + ", enabled="
        + enabled
        + ", credentials=<redacted>]";
  }
}
