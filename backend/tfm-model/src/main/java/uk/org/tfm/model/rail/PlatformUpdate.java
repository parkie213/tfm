package uk.org.tfm.model.rail;

import java.time.Instant;

/**
 * An immutable snapshot of the platform assignment for one train at one location, extracted from a
 * Darwin Push Port {@code TS/Location/plat} element.
 *
 * @param rid Darwin RTTI Train ID (the {@code rid} attribute on the enclosing {@code TS}).
 * @param tpl TIPLOC of the location ({@code tpl} attribute on {@code Location}).
 * @param platform Platform string (e.g. {@code "3"}, {@code "3A"}); may be empty if the element was
 *     present but carried no text.
 * @param confirmed {@code true} if the {@code conf} attribute was {@code "true"} or {@code "1"},
 *     meaning the platform is operationally confirmed rather than predicted/planned.
 * @param suppressed {@code true} if either {@code platsup} (public-suppression) or {@code
 *     cisPlatsup} (operator-suppression) was set; callers should not display the platform to
 *     passengers when this is true.
 * @param source Origin of the platform data: {@code "P"} = planned, {@code "A"} = auto-assigned,
 *     {@code "M"} = manually entered. Mirrors the Darwin {@code platsrc} attribute.
 * @param at Instant when this update was parsed; used as the "newest wins" ordering key in the
 *     application platform index.
 * @param arrAt actual arrival time from {@code Location/arr/@at} ({@code HH:MM[:SS]}); empty if the
 *     location carried no actual arrival. With {@link #depAt} this gives the "at platform now"
 *     signal: arrived ({@code arrAt} non-empty) but not yet departed.
 * @param depAt actual departure time from {@code Location/dep/@at}; empty if not yet departed.
 */
public record PlatformUpdate(
    String rid,
    String tpl,
    String platform,
    boolean confirmed,
    boolean suppressed,
    String source,
    Instant at,
    String arrAt,
    String depAt) {

  /**
   * Back-compat constructor for callers with no movement actuals (tests and simple feed clients).
   */
  public PlatformUpdate(
      String rid,
      String tpl,
      String platform,
      boolean confirmed,
      boolean suppressed,
      String source,
      Instant at) {
    this(rid, tpl, platform, confirmed, suppressed, source, at, "", "");
  }

  /**
   * True when the service has arrived at this location but not yet departed — i.e. at the platform.
   */
  public boolean atPlatformNow() {
    return !arrAt.isEmpty() && depAt.isEmpty();
  }
}
