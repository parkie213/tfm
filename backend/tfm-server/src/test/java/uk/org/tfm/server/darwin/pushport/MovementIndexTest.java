package uk.org.tfm.server.darwin.pushport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.rail.MovementSnapshot;

/** Unit tests for {@link MovementIndex} — the field-level merge lifecycle and TTL sweep. */
class MovementIndexTest {

  private static MovementSnapshot snap(
      String tpl, String arrAt, String arrEt, String depAt, String depEt, Instant at) {
    return new MovementSnapshot("rid", tpl, arrAt, arrEt, depAt, depEt, "", "", at);
  }

  @Test
  void laterEstimateDoesNotClearKnownActual() {
    MovementIndex idx = new MovementIndex();
    Instant t0 = Instant.parse("2026-06-14T17:00:00Z");
    // 1) Arrived (arr.at) — at the platform.
    idx.merge("1F77", snap("NTNG", "16:58", "", "", "", t0));
    assertTrue(idx.lookup("1F77", "NTNG").orElseThrow().atPlatformNow());
    // 2) A later message carries only a dep ESTIMATE — must NOT clear the known arr.at.
    idx.merge("1F77", snap("NTNG", "", "", "", "17:05", t0.plusSeconds(30)));
    MovementSnapshot m = idx.lookup("1F77", "NTNG").orElseThrow();
    assertEquals("16:58", m.arrAt(), "arr.at retained across an et-only update");
    assertEquals("17:05", m.depEt(), "dep et merged in");
    assertTrue(m.atPlatformNow(), "still at platform: arrived, dep is only estimated");
    // 3) Actual departure flips it.
    idx.merge("1F77", snap("NTNG", "", "", "17:06", "", t0.plusSeconds(60)));
    assertFalse(
        idx.lookup("1F77", "NTNG").orElseThrow().atPlatformNow(), "departed → not at platform");
  }

  @Test
  void atAdvancesToNewerMessage() {
    MovementIndex idx = new MovementIndex();
    Instant t0 = Instant.parse("2026-06-14T17:00:00Z");
    idx.merge("1F77", snap("NTNG", "16:58", "", "", "", t0));
    idx.merge("1F77", snap("NTNG", "", "", "", "17:05", t0.plusSeconds(30)));
    assertEquals(t0.plusSeconds(30), idx.lookup("1F77", "NTNG").orElseThrow().at());
  }

  @Test
  void sweepEvictsStaleEntries() {
    MovementIndex idx = new MovementIndex();
    Instant t0 = Instant.parse("2026-06-14T17:00:00Z");
    idx.merge("1F77", snap("NTNG", "16:58", "", "", "", t0));
    assertEquals(0, idx.sweep(t0.plusSeconds(60), Duration.ofMinutes(30)), "fresh entry kept");
    assertEquals(
        1, idx.sweep(t0.plus(Duration.ofMinutes(31)), Duration.ofMinutes(30)), "stale evicted");
    assertEquals(0, idx.size());
  }

  @Test
  void keysByHeadcodeAndTpl() {
    MovementIndex idx = new MovementIndex();
    Instant t0 = Instant.parse("2026-06-14T17:00:00Z");
    idx.merge("1F77", snap("NTNG", "16:58", "", "", "", t0));
    assertTrue(idx.lookup("9Z99", "NTNG").isEmpty(), "different headcode → no entry");
    assertTrue(idx.lookup("1F77", "LEIR").isEmpty(), "different tpl → no entry");
    assertTrue(idx.lookup("1F77", "NTNG").isPresent());
  }
}
