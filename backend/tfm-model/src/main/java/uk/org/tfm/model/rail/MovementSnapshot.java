package uk.org.tfm.model.rail;

import java.time.Instant;

/**
 * One train's movement actuals/estimates at one location, extracted from a Darwin push-port {@code
 * TS/Location} element ({@code arr}/{@code dep}/{@code pass} children). Distinct from {@link
 * PlatformUpdate}: a movement is emitted for <em>every</em> in-scope location carrying a time,
 * whether or not it also carries a {@code plat} — the TS firehose updates movement far more often
 * than platform, and the "at platform now" + position signals must not be gated on a platform
 * element being co-present.
 *
 * <p>Each child can carry an actual ({@code @at}) and/or an estimate ({@code @et}, public, or
 * {@code @wet}, working — captured as one {@code …Et} field, public preferred). A single message
 * typically carries only a subset; the lifecycle across messages is {@code et-only → arr.at +
 * dep.et → arr.at + dep.at}. Field-level merging — so a later estimate never clears a known actual
 * — is the responsibility of the application movement index, not this snapshot.
 *
 * @param rid Darwin RTTI Train ID (the {@code rid} on the enclosing {@code TS}).
 * @param tpl TIPLOC of the location.
 * @param arrAt actual arrival ({@code HH:MM[:SS]}); empty if none in this message.
 * @param arrEt estimated arrival (public {@code et}, else working {@code wet}); empty if none.
 * @param depAt actual departure; empty if not yet departed in this message.
 * @param depEt estimated departure; empty if none.
 * @param passAt actual pass (non-stopping); empty if none.
 * @param passEt estimated pass; empty if none.
 * @param at instant this snapshot was parsed; the merge/TTL ordering key in the application
 *     movement index.
 */
public record MovementSnapshot(
    String rid,
    String tpl,
    String arrAt,
    String arrEt,
    String depAt,
    String depEt,
    String passAt,
    String passEt,
    Instant at) {

  /**
   * True when this snapshot carried at least one actual or estimate (otherwise it is not emitted).
   */
  public boolean hasData() {
    return !arrAt.isEmpty()
        || !arrEt.isEmpty()
        || !depAt.isEmpty()
        || !depEt.isEmpty()
        || !passAt.isEmpty()
        || !passEt.isEmpty();
  }

  /** True when the service has arrived but not yet departed — i.e. standing at the platform now. */
  public boolean atPlatformNow() {
    return !arrAt.isEmpty() && depAt.isEmpty();
  }

  /**
   * The forward ETA at this location — the estimated arrival, or the estimated pass for a
   * non-stopping service. Empty when only actuals (or nothing) are known. Used by the position
   * estimator as a forward interpolation target.
   */
  public String forwardEt() {
    return !arrEt.isEmpty() ? arrEt : passEt;
  }
}
