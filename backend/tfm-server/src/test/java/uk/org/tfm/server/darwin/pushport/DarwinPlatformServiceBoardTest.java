package uk.org.tfm.server.darwin.pushport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.rail.MovementSnapshot;
import uk.org.tfm.model.rail.PlatformUpdate;
import uk.org.tfm.model.storage.StationPlatformStore;
import uk.org.tfm.model.storage.TrainScheduleStore;

/** Platform sorting, suppression, and occupant composition using model storage fakes. */
class DarwinPlatformServiceBoardTest {

  private static final PlatformScope SCOPE = new PlatformScope();
  private static final TrainScheduleStore EMPTY_DAO = new StubTrainScheduleStore();
  private static final StationPlatformStore EMPTY_PLATFORM_DAO =
      new StationPlatformStore() {
        public Map<String, Set<String>> loadAll() {
          return Map.of();
        }

        public void upsertBatch(Map<String, Set<String>> labels, Instant at) {}
      };

  // ---- natural sort tests ----

  private static final Comparator<String> CMP = DarwinPlatformService.PLATFORM_COMPARATOR;

  @Test
  void naturalSortOrdersNumericPrefixAscending() {
    List<String> platforms = Arrays.asList("10", "3A", "2", "1");
    platforms.sort(CMP);
    assertEquals(List.of("1", "2", "3A", "10"), platforms, "platforms must sort: 1, 2, 3A, 10");
  }

  @Test
  void naturalSortPutsAlphaSuffixAfterSameNumericPrefix() {
    // "3" < "3A" < "3B" < "10"
    List<String> platforms = Arrays.asList("10", "3B", "3", "3A");
    platforms.sort(CMP);
    assertEquals(List.of("3", "3A", "3B", "10"), platforms);
  }

  @Test
  void naturalSortNonNumericLabelsLastInLexOrder() {
    // No leading digit → sort after all numeric-prefixed entries.
    List<String> platforms = Arrays.asList("2", "Bay", "1", "Depot");
    platforms.sort(CMP);
    assertEquals(
        List.of("1", "2", "Bay", "Depot"),
        platforms,
        "non-numeric platform labels must follow all numeric-prefixed ones");
  }

  @Test
  void naturalSortSingleDigitVsDoubleDigit() {
    assertTrue(CMP.compare("9", "10") < 0, "9 must sort before 10 (not lexicographic)");
  }

  @Test
  void naturalSortIdenticalLabelsCompareEqual() {
    assertEquals(0, CMP.compare("3A", "3A"));
  }

  // ---- board construction tests ----

  private DarwinPlatformService makeService(PlatformIndex idx) {
    return new DarwinPlatformService(idx, EMPTY_DAO, EMPTY_PLATFORM_DAO, SCOPE);
  }

  @Test
  void boardShowsNonSuppressedOccupantWithHeadcode() {
    PlatformIndex idx = new PlatformIndex();
    idx.put("1Y29", new PlatformUpdate("rid1", "NTNG", "5", true, false, "P", Instant.now()));

    List<DarwinPlatformService.PlatformLine> board = makeService(idx).stationBoard("NTNG");

    assertEquals(1, board.size(), "one platform in the board");
    assertEquals("5", board.get(0).platform());
    assertEquals(1, board.get(0).trains().size());
    assertEquals("1Y29", board.get(0).trains().get(0).headcode());
    assertTrue(board.get(0).trains().get(0).confirmed());
  }

  @Test
  void suppressedUpdateDoesNotAppearOnBoard() {
    PlatformIndex idx = new PlatformIndex();
    // Injected directly into the index (bypassing onUpdate), so knownPlatforms is also empty.
    idx.put("1M26", new PlatformUpdate("rid1", "NTNG", "3", false, true, "P", Instant.now()));

    List<DarwinPlatformService.PlatformLine> board = makeService(idx).stationBoard("NTNG");
    assertTrue(board.isEmpty(), "a suppressed update must not appear on the board");
  }

  @Test
  void emptyBoardForUnknownTpl() {
    PlatformIndex idx = new PlatformIndex();
    idx.put("1M26", new PlatformUpdate("rid1", "NTNG", "3", false, false, "P", Instant.now()));

    assertTrue(
        makeService(idx).stationBoard("LEICR").isEmpty(),
        "board for a different TPL must be empty");
  }

  @Test
  void multiplePlatformsReturnedInNaturalOrder() {
    PlatformIndex idx = new PlatformIndex();
    Instant now = Instant.now();
    idx.put("1M26", new PlatformUpdate("rid1", "NTNG", "10", false, false, "P", now));
    idx.put("1L50", new PlatformUpdate("rid2", "NTNG", "2", false, false, "P", now));
    idx.put("1Y29", new PlatformUpdate("rid3", "NTNG", "3A", false, false, "P", now));

    List<DarwinPlatformService.PlatformLine> board = makeService(idx).stationBoard("NTNG");

    assertEquals(3, board.size());
    assertEquals("2", board.get(0).platform());
    assertEquals("3A", board.get(1).platform());
    assertEquals("10", board.get(2).platform());
  }

  @Test
  void occupantMarkedAtPlatformWhenDarwinMovementArrived() {
    // A DAO that resolves the rid → headcode (no DB) so onUpdate/onMovementSnapshot correlate.
    TrainScheduleStore resolvingDao =
        new StubTrainScheduleStore() {
          @Override
          public Optional<String> headcodeForRid(String rid) {
            return Optional.of("1F77");
          }
        };
    DarwinPlatformService svc =
        new DarwinPlatformService(new PlatformIndex(), resolvingDao, EMPTY_PLATFORM_DAO, SCOPE);

    Instant now = Instant.now();
    svc.onUpdate(new PlatformUpdate("rid1", "NTNG", "3", true, false, "P", now));
    // Movement-only snapshot: arrived (arr.at), not departed → at the platform now.
    svc.onMovementSnapshot(
        new MovementSnapshot("rid1", "NTNG", "16:58", "", "", "17:01", "", "", now));

    DarwinPlatformService.Occupant occ = svc.stationBoard("NTNG").get(0).trains().get(0);
    assertTrue(occ.atPlatformNow(), "arr.at present, no dep.at → at platform now");

    // A later actual departure clears it (newest dep.at merged in).
    svc.onMovementSnapshot(
        new MovementSnapshot(
            "rid1", "NTNG", "16:58", "", "17:02", "", "", "", now.plusSeconds(60)));
    assertFalse(
        svc.stationBoard("NTNG").get(0).trains().get(0).atPlatformNow(),
        "departed → no longer at platform");
  }

  @Test
  void occupantDegradesToEmptyStringsWhenScheduleAbsent() {
    // The schedule store returns Optional.empty() → origin/destination = "".
    PlatformIndex idx = new PlatformIndex();
    idx.put("1M26", new PlatformUpdate("rid1", "NTNG", "3", false, false, "P", Instant.now()));

    DarwinPlatformService.Occupant occupant =
        makeService(idx).stationBoard("NTNG").get(0).trains().get(0);
    assertEquals("", occupant.origin(), "no schedule → origin must be empty");
    assertEquals("", occupant.destination(), "no schedule → destination must be empty");
    assertEquals("1M26", occupant.headcode());
  }
}
