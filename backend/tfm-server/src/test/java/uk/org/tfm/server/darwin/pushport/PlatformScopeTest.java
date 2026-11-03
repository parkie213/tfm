package uk.org.tfm.server.darwin.pushport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PlatformScope#tplForCrs(String)}. Uses the bundled
 * naptan/rail-references.csv so that the scope reflects the national CRS codes.
 */
class PlatformScopeTest {

  private static PlatformScope scope;

  @BeforeAll
  static void buildScope() {
    scope = new PlatformScope();
  }

  @Test
  void nottinghamCrsResolvesToNtng() {
    Optional<String> tpl = scope.tplForCrs("NOT");
    assertTrue(tpl.isPresent(), "NOT (Nottingham) must be in scope");
    assertEquals("NTNG", tpl.get(), "NOT must map to TIPLOC NTNG");
  }

  @Test
  void lookupIsCaseInsensitiveOnCrs() {
    // CRS codes are conventionally upper-case but the API should tolerate lower/mixed.
    Optional<String> lower = scope.tplForCrs("not");
    Optional<String> upper = scope.tplForCrs("NOT");
    assertEquals(upper, lower, "tplForCrs must be case-insensitive");
  }

  @Test
  void unknownCrsReturnsEmpty() {
    Optional<String> result = scope.tplForCrs("ZZZ");
    assertTrue(result.isEmpty(), "an unknown CRS must return Optional.empty");
  }

  @Test
  void nullCrsReturnsEmpty() {
    assertTrue(scope.tplForCrs(null).isEmpty(), "null CRS must return Optional.empty");
  }

  @Test
  void blankCrsReturnsEmpty() {
    assertTrue(scope.tplForCrs("").isEmpty(), "blank CRS must return Optional.empty");
    assertTrue(scope.tplForCrs("  ").isEmpty(), "whitespace-only CRS must return Optional.empty");
  }

  @Test
  void leicesterCrsResolvesToLester() {
    Optional<String> tpl = scope.tplForCrs("LEI");
    assertTrue(tpl.isPresent(), "LEI (Leicester) must be in scope");
    assertEquals("LESTER", tpl.get(), "LEI must map to TIPLOC LESTER");
  }

  // ---- nameForCrs tests ----

  @Test
  void nameForCrsStripsRailStationSuffix() {
    Optional<String> name = scope.nameForCrs("NOT");
    assertTrue(name.isPresent(), "NOT (Nottingham) must have a display name");
    assertEquals("Nottingham", name.get(), "nameForCrs must strip ' Rail Station' suffix");
  }

  @Test
  void nameForCrsIsCaseInsensitive() {
    assertEquals(
        scope.nameForCrs("NOT"), scope.nameForCrs("not"), "nameForCrs must be case-insensitive");
  }

  @Test
  void nameForCrsUnknownReturnsEmpty() {
    assertTrue(scope.nameForCrs("ZZZ").isEmpty(), "unknown CRS must return Optional.empty");
  }

  @Test
  void nameForCrsNullReturnsEmpty() {
    assertTrue(scope.nameForCrs(null).isEmpty(), "null CRS must return Optional.empty");
  }

  // ---- nameForTpl tests ----

  @Test
  void nameForTplStripsRailStationSuffix() {
    Optional<String> name = scope.nameForTpl("NTNG");
    assertTrue(name.isPresent(), "NTNG must have a display name");
    assertEquals("Nottingham", name.get(), "nameForTpl must strip ' Rail Station' suffix");
  }

  @Test
  void nameForTplIsCaseInsensitive() {
    assertEquals(
        scope.nameForTpl("NTNG"), scope.nameForTpl("ntng"), "nameForTpl must be case-insensitive");
  }

  @Test
  void nameForTplUnknownReturnsEmpty() {
    assertTrue(scope.nameForTpl("XXXXXX").isEmpty(), "unknown TIPLOC must return Optional.empty");
  }

  @Test
  void nameForTplNullReturnsEmpty() {
    assertTrue(scope.nameForTpl(null).isEmpty(), "null TIPLOC must return Optional.empty");
  }

  // ---- latLngForTpl tests ----

  @Test
  void latLngForNottinghamIsCorrect() {
    // NTNG: 52.94707..., -1.14638... from the NaPTAN CSV.
    Optional<double[]> coords = scope.latLngForTpl("NTNG");
    assertTrue(coords.isPresent(), "NTNG must have coordinates");
    assertEquals(52.947, coords.get()[0], 0.01, "NTNG lat ~52.947");
    assertEquals(-1.146, coords.get()[1], 0.01, "NTNG lng ~-1.146");
  }

  @Test
  void latLngForTplIsCaseInsensitive() {
    Optional<double[]> upper = scope.latLngForTpl("NTNG");
    Optional<double[]> lower = scope.latLngForTpl("ntng");
    assertTrue(upper.isPresent());
    assertTrue(lower.isPresent());
    assertEquals(upper.get()[0], lower.get()[0], 1e-9, "lat must be identical regardless of case");
    assertEquals(upper.get()[1], lower.get()[1], 1e-9, "lng must be identical regardless of case");
  }

  @Test
  void latLngForUnknownTplReturnsEmpty() {
    assertTrue(scope.latLngForTpl("XXXXXX").isEmpty(), "unknown TIPLOC must return Optional.empty");
  }

  @Test
  void latLngForNullTplReturnsEmpty() {
    assertTrue(scope.latLngForTpl(null).isEmpty(), "null TIPLOC must return Optional.empty");
  }

  // ---- tplForAnyCrs tests (all-stations lookup) ----

  @Test
  void tplForAnyCrsResolvesGrantham() {
    // Grantham (GRA) is NOT an anchor station but must resolve via the all-stations map.
    Optional<String> tpl = scope.tplForAnyCrs("GRA");
    assertTrue(tpl.isPresent(), "GRA (Grantham) must resolve via the all-stations map");
    assertEquals("GTHM", tpl.get());
  }

  @Test
  void tplForAnyCrsIsCaseInsensitive() {
    assertEquals(
        scope.tplForAnyCrs("GRA"),
        scope.tplForAnyCrs("gra"),
        "tplForAnyCrs must be case-insensitive");
  }

  @Test
  void tplForAnyCrsUnknownReturnsEmpty() {
    assertTrue(scope.tplForAnyCrs("ZZZ").isEmpty());
  }
}
