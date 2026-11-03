package uk.org.tfm.server.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * Guards the geometry freshness-key composition — the value the geometry gate compares to decide
 * whether to rebuild. The gate itself is {@code key.equals(stored) && builtAt != null}; correctness
 * hinges on the key changing iff an input (feed / OSRM build / algo version) changed. Under-
 * invalidation (key collides across different inputs → serve stale geometry) is the failure we must
 * never allow, so these assert sensitivity to each runtime component.
 */
final class BusGeometryBuilderFreshnessTest {

  @Test
  void sameInputsProduceSameKey() {
    assertEquals(
        BusGeometryBuilder.geometryKey("feed-abc", "osrm-1"),
        BusGeometryBuilder.geometryKey("feed-abc", "osrm-1"),
        "identical inputs must yield an identical key (so an unchanged restart skips)");
  }

  @Test
  void feedChangeChangesKey() {
    assertNotEquals(
        BusGeometryBuilder.geometryKey("feed-abc", "osrm-1"),
        BusGeometryBuilder.geometryKey("feed-XYZ", "osrm-1"),
        "a new feed must force a geometry rebuild");
  }

  @Test
  void osrmBuildChangeChangesKey() {
    assertNotEquals(
        BusGeometryBuilder.geometryKey("feed-abc", "osrm-1"),
        BusGeometryBuilder.geometryKey("feed-abc", "osrm-2"),
        "rebuilding the OSRM network (new OSRM_VERSION) must force a re-snap");
  }

  @Test
  void keyIsAStableSha256Hex() {
    String key = BusGeometryBuilder.geometryKey("feed-abc", "osrm-1");
    assertEquals(64, key.length(), "SHA-256 hex is 64 chars");
    assertEquals(key, key.toLowerCase(), "lower-case hex");
  }
}
