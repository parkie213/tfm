package uk.org.tfm.model.rail;

/**
 * A serialized stream of platform, movement, and train-schedule updates, independent of transport.
 */
public interface TrainEventFeed {

  /**
   * Starts delivering parsed messages to {@code sink} on a background thread. The sink is invoked
   * from the feed's own thread and is never called concurrently for a single feed, so consumers can
   * keep per-train state without synchronising on the write path. Idempotent: a second call while
   * running is a no-op. A feed with no credentials configured starts as a no-op (logs a warning).
   */
  void start(TrainEventSink sink);

  /** Stops the feed and releases its consumer/threads. Idempotent. */
  void stop();
}
