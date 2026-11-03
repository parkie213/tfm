package uk.org.tfm.model.rail;

/**
 * Receives platform, schedule, and movement events from a train-event feed. Invoked on the feed's
 * own consumer thread, never concurrently for a single feed, so implementations may keep per-train
 * state without synchronising the write path. Implementations must not throw.
 */
public interface TrainEventSink {

  /** A platform assignment (with movement actuals) from a {@code TS} message. */
  void onPlatformUpdate(PlatformUpdate update);

  /**
   * A live schedule (rid → headcode → O/D + calling points) from a {@code schedule} (SC) message.
   */
  void onScheduleRecord(ScheduleRecord record);

  /**
   * A movement actual/estimate at one location from a {@code TS} message (drives "at platform
   * now").
   */
  void onMovement(MovementSnapshot movement);
}
