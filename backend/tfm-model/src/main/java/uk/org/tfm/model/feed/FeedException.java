package uk.org.tfm.model.feed;

import java.time.Duration;
import java.util.Optional;

/** A feed failure classified without exposing its transport protocol to application services. */
public final class FeedException extends Exception {
  public enum Kind {
    RATE_LIMITED,
    UNAVAILABLE,
    INVALID_RESPONSE
  }

  private final Kind kind;
  private final Optional<Duration> retryAfter;

  public FeedException(String message, Kind kind, Optional<Duration> retryAfter) {
    super(message);
    this.kind = kind;
    this.retryAfter = retryAfter;
  }

  public FeedException(String message, Kind kind, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.retryAfter = Optional.empty();
  }

  public Kind kind() {
    return kind;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }
}
