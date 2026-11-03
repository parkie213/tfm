package uk.org.tfm.server.darwin.pushport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.rail.PlatformUpdate;

/** Unit tests for {@link PlatformIndex}. Index is now keyed by (headcode, tpl). */
class PlatformIndexTest {

  private PlatformIndex index;

  @BeforeEach
  void setUp() {
    index = new PlatformIndex();
  }

  @Test
  void lookupMissReturnsEmpty() {
    Optional<PlatformUpdate> result = index.lookup("1M26", "NTNG");
    assertTrue(result.isEmpty(), "a lookup on an empty index must return Optional.empty");
  }

  @Test
  void putAndLookupRoundTrip() {
    PlatformUpdate u = update("rid1", "NTNG", "3", Instant.now());
    index.put("1M26", u);

    Optional<PlatformUpdate> result = index.lookup("1M26", "NTNG");
    assertTrue(result.isPresent());
    assertEquals("3", result.get().platform());
  }

  @Test
  void newerAtWinsOnPut() {
    Instant older = Instant.parse("2026-06-12T10:00:00Z");
    Instant newer = older.plusSeconds(60);

    index.put("1M26", update("rid1", "NTNG", "3", older));
    index.put("1M26", update("rid1", "NTNG", "4", newer)); // newer — should win

    assertEquals("4", index.lookup("1M26", "NTNG").orElseThrow().platform());
  }

  @Test
  void olderAtDoesNotOverwriteNewer() {
    Instant older = Instant.parse("2026-06-12T10:00:00Z");
    Instant newer = older.plusSeconds(60);

    index.put("1M26", update("rid1", "NTNG", "4", newer)); // put newer first
    index.put("1M26", update("rid1", "NTNG", "3", older)); // then try to overwrite with older

    assertEquals(
        "4",
        index.lookup("1M26", "NTNG").orElseThrow().platform(),
        "an older update must not overwrite a newer one");
  }

  @Test
  void sweepEvictsEntriesOlderThanTtl() {
    Instant now = Instant.now();
    Duration ttl = Duration.ofMinutes(30);

    // Entry that is 31 minutes old — should be evicted.
    index.put("1M26", update("ridA", "NTNG", "1", now.minus(Duration.ofMinutes(31))));
    // Entry that is 29 minutes old — should survive.
    index.put("1L50", update("ridB", "LEI", "2", now.minus(Duration.ofMinutes(29))));

    int removed = index.sweep(now, ttl);

    assertEquals(1, removed, "exactly one stale entry should be evicted");
    assertEquals(1, index.size());
    assertTrue(index.lookup("1M26", "NTNG").isEmpty(), "stale entry must be gone");
    assertTrue(index.lookup("1L50", "LEI").isPresent(), "fresh entry must survive");
  }

  @Test
  void sweepReturnsZeroWhenNothingIsStale() {
    index.put("1M26", update("rid1", "NTNG", "3", Instant.now()));
    int removed = index.sweep(Instant.now(), Duration.ofMinutes(30));
    assertEquals(0, removed);
    assertEquals(1, index.size());
  }

  @Test
  void sweepClearsEverythingWhenAllStale() {
    Instant veryOld = Instant.parse("2026-01-01T00:00:00Z");
    index.put("1M26", update("ridA", "NTNG", "1", veryOld));
    index.put("1L50", update("ridB", "LEI", "2", veryOld));

    int removed = index.sweep(Instant.now(), Duration.ofMinutes(1));
    assertEquals(2, removed);
    assertEquals(0, index.size());
  }

  @Test
  void differentHeadcodeTplPairsAreStoredIndependently() {
    // Same headcode, different TPLs; different headcode, same TPL.
    index.put("1M26", update("rid1", "NTNG", "3", Instant.now()));
    index.put("1M26", update("rid1", "LEICR", "1", Instant.now()));
    index.put("1L50", update("rid2", "NTNG", "5", Instant.now()));

    assertEquals(3, index.size());
    assertEquals("3", index.lookup("1M26", "NTNG").orElseThrow().platform());
    assertEquals("1", index.lookup("1M26", "LEICR").orElseThrow().platform());
    assertEquals("5", index.lookup("1L50", "NTNG").orElseThrow().platform());
  }

  // Verify that PlatformUpdate.rid() is preserved even though it is not the index key.
  @Test
  void ridFieldIsPreservedOnUpdate() {
    PlatformUpdate u = update("202606121234567", "NTNG", "3", Instant.now());
    index.put("1M26", u);
    assertEquals(
        "202606121234567",
        index.lookup("1M26", "NTNG").orElseThrow().rid(),
        "rid is retained on PlatformUpdate for debug/reference");
  }

  // ---- Stored / forTpl tests ----

  @Test
  void forTplReturnsAllNonSuppressedEntriesForTpl() {
    Instant now = Instant.now();
    index.put("1M26", update("rid1", "NTNG", "3", now));
    index.put("1L50", update("rid2", "NTNG", "1", now));
    // Different TPL — must not appear.
    index.put("1A00", update("rid3", "LEICR", "2", now));

    List<PlatformIndex.Stored> result = index.forTpl("NTNG");
    assertEquals(2, result.size(), "both non-suppressed NTNG entries must be returned");
    assertTrue(result.stream().allMatch(s -> s.update().tpl().equals("NTNG")));
  }

  @Test
  void forTplExcludesSuppressedEntries() {
    Instant now = Instant.now();
    // Not suppressed.
    index.put("1M26", update("rid1", "NTNG", "3", now));
    // Suppressed — must be excluded.
    PlatformUpdate suppressed = new PlatformUpdate("rid2", "NTNG", "4", false, true, "P", now);
    index.put("1L50", suppressed);

    List<PlatformIndex.Stored> result = index.forTpl("NTNG");
    assertEquals(1, result.size(), "suppressed entry must be excluded from forTpl");
    assertEquals("1M26", result.get(0).headcode());
  }

  @Test
  void forTplIsCaseInsensitive() {
    index.put("1M26", update("rid1", "NTNG", "3", Instant.now()));
    // Both lower and upper should find the same entry.
    assertEquals(1, index.forTpl("ntng").size());
    assertEquals(1, index.forTpl("NTNG").size());
  }

  @Test
  void forTplReturnsEmptyWhenNothingAtTpl() {
    index.put("1M26", update("rid1", "NTNG", "3", Instant.now()));
    assertTrue(
        index.forTpl("LEICR").isEmpty(), "forTpl must return empty for a TPL with no entries");
  }

  @Test
  void storedHeadcodeMatchesPutHeadcode() {
    index.put("1Y29", update("rid1", "NTNG", "5", Instant.now()));
    List<PlatformIndex.Stored> stored = index.forTpl("NTNG");
    assertEquals(1, stored.size());
    assertEquals(
        "1Y29",
        stored.get(0).headcode(),
        "Stored.headcode() must equal the headcode passed to put()");
  }

  // ---- helper ----

  private static PlatformUpdate update(String rid, String tpl, String platform, Instant at) {
    return new PlatformUpdate(rid, tpl, platform, false, false, "P", at);
  }
}
