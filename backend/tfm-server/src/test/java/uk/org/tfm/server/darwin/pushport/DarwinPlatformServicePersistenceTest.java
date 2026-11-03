package uk.org.tfm.server.darwin.pushport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.rail.PlatformUpdate;
import uk.org.tfm.model.rail.TrainSchedule;
import uk.org.tfm.model.storage.StationPlatformStore;
import uk.org.tfm.model.storage.TrainScheduleStore;

/**
 * Tests the persistence + timetable-decoupling behaviour added to {@link DarwinPlatformService}:
 *
 * <ul>
 *   <li>{@code knownPlatforms} is seeded from the DB on {@link DarwinPlatformService#start()} so a
 *       cold board shows the full platform layout even with the feed disabled.
 *   <li>{@code onUpdate} records the platform label even when the rid resolves to no schedule — the
 *       inventory is independent of the Darwin Timetable Files.
 *   <li>the sweep flush upserts only the newly-dirty TIPLOCs, then clears the dirty marks.
 *   <li>suppressed labels never enter the inventory.
 * </ul>
 *
 * Package-level stubs (DAOs subclassed with canned/capturing behaviour), no Testcontainers —
 * consistent with the rest of the suite.
 */
class DarwinPlatformServicePersistenceTest {

  private static final PlatformScope SCOPE = new PlatformScope();

  /** Schedule DAO that resolves nothing: no rid maps to a headcode, no schedule for byRid. */
  private static TrainScheduleStore noSchedule() {
    return new StubTrainScheduleStore() {
      @Override
      public Optional<String> headcodeForRid(String rid) {
        return Optional.empty();
      }

      @Override
      public Optional<TrainSchedule> byRid(String rid) {
        return Optional.empty();
      }
    };
  }

  /** Platform DAO with canned seed data and a capturing upsert; its DataSource is never touched. */
  private static final class StubPlatformDao implements StationPlatformStore {
    private final Map<String, Set<String>> seed;
    Map<String, Set<String>> lastUpsert;
    int upsertCalls;

    StubPlatformDao(Map<String, Set<String>> seed) {
      this.seed = seed;
    }

    @Override
    public Map<String, Set<String>> loadAll() {
      return new HashMap<>(seed);
    }

    @Override
    public void upsertBatch(Map<String, Set<String>> observations, Instant observedAt) {
      upsertCalls++;
      lastUpsert = observations;
    }
  }

  private static DarwinPlatformService service(StubPlatformDao platformDao) {
    return new DarwinPlatformService(new PlatformIndex(), noSchedule(), platformDao, SCOPE);
  }

  @Test
  void seedsKnownPlatformsFromDbOnStart() {
    DarwinPlatformService svc = service(new StubPlatformDao(Map.of("NTNG", Set.of("1", "2"))));

    svc.start(false); // feed disabled → start() still seeds the inventory

    List<DarwinPlatformService.PlatformLine> board = svc.stationBoard("NTNG");
    assertEquals(
        List.of("1", "2"),
        board.stream().map(DarwinPlatformService.PlatformLine::platform).toList(),
        "cold board shows the full seeded layout in natural order");
    assertTrue(board.stream().allMatch(l -> l.trains().isEmpty()), "no live occupants yet");
  }

  @Test
  void onUpdateRecordsPlatformWhenRidHasNoSchedule() {
    DarwinPlatformService svc = service(new StubPlatformDao(Map.of()));

    // rid 'ridX' resolves to no headcode (noSchedule) — the occupant is dropped, the label is not.
    svc.onUpdate(new PlatformUpdate("ridX", "NTNG", "5", false, false, "P", Instant.now()));

    List<DarwinPlatformService.PlatformLine> board = svc.stationBoard("NTNG");
    assertEquals(1, board.size());
    assertEquals("5", board.get(0).platform());
    assertTrue(board.get(0).trains().isEmpty(), "no schedule → label kept, no occupant");
  }

  @Test
  void flushUpsertsDirtyTiplocsThenClears() {
    StubPlatformDao dao = new StubPlatformDao(Map.of());
    DarwinPlatformService svc = service(dao);

    svc.onUpdate(new PlatformUpdate("ridX", "NTNG", "5", false, false, "P", Instant.now()));
    svc.flushDirtyPlatforms();

    assertEquals(1, dao.upsertCalls, "one batch flushed");
    assertEquals(Set.of("5"), dao.lastUpsert.get("NTNG"), "the new label is in the batch");

    svc.flushDirtyPlatforms(); // nothing new since → dirty set cleared → no-op
    assertEquals(1, dao.upsertCalls, "no new labels → no further upsert");
  }

  @Test
  void suppressedLabelNeverEntersInventory() {
    StubPlatformDao dao = new StubPlatformDao(Map.of());
    DarwinPlatformService svc = service(dao);

    svc.onUpdate(new PlatformUpdate("ridX", "NTNG", "5", false, true, "P", Instant.now()));
    svc.flushDirtyPlatforms();

    assertEquals(0, dao.upsertCalls, "suppressed update must not be persisted");
    assertTrue(
        svc.stationBoard("NTNG").isEmpty(), "suppressed update must not appear on the board");
  }
}
