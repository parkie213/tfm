package uk.org.tfm.model.board;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One arrival or departure. Times are local HH:mm values; expectedTime contains only a clock time.
 * An absent prediction or delay means unknown. Status is independent of whether a time is reported.
 * Identifiers are opaque and provider-scoped; empty text denotes an unreported label or identifier.
 */
public record BoardEntry(
    String serviceId,
    String scheduledTime,
    Optional<String> expectedTime,
    String destination,
    String origin,
    String operator,
    String platform,
    Status status,
    boolean live,
    OptionalInt delayMinutes,
    int coachCount,
    List<Coach> coaches) {
  public enum Status {
    ON_TIME,
    DELAYED,
    CANCELLED,
    SCHEDULED,
    UNKNOWN
  }

  public BoardEntry {
    coaches = List.copyOf(coaches);
  }
}
