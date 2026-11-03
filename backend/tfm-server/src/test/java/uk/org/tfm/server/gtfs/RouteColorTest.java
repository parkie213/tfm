package uk.org.tfm.server.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link RouteColor} — GTFS-colour preference + byte-for-byte hash parity. */
class RouteColorTest {

  /**
   * hashColor must reproduce the frontend's former djb2 → HSL output EXACTLY, so routes with no
   * official GTFS colour keep the colour they had before this moved server-side. Expected values
   * are the verbatim output of the original JS {@code colorForRoute} (see the screenshot's legend
   * routes plus a few edge cases).
   */
  @Test
  void hashColorMatchesLegacyJsHash() {
    assertEquals("hsl(76, 70%, 42%)", RouteColor.hashColor("1"));
    assertEquals("hsl(79, 70%, 42%)", RouteColor.hashColor("2"));
    assertEquals("hsl(351, 70%, 42%)", RouteColor.hashColor("3A"));
    assertEquals("hsl(75, 70%, 42%)", RouteColor.hashColor("6"));
    assertEquals("hsl(5, 70%, 42%)", RouteColor.hashColor("11"));
    assertEquals("hsl(102, 70%, 42%)", RouteColor.hashColor("11C"));
    assertEquals("hsl(13, 70%, 42%)", RouteColor.hashColor("19"));
    assertEquals("hsl(281, 70%, 42%)", RouteColor.hashColor("5A"));
    assertEquals("hsl(224, 70%, 42%)", RouteColor.hashColor("X5"));
    assertEquals("hsl(60, 70%, 42%)", RouteColor.hashColor("88A"));
  }

  /** Blank / whitespace-only names collapse to the 'route' sentinel, as in the JS. */
  @Test
  void hashColorBlankFallsBackToSentinel() {
    String sentinel = "hsl(204, 70%, 42%)";
    assertEquals(sentinel, RouteColor.hashColor(""));
    assertEquals(sentinel, RouteColor.hashColor("   "));
    assertEquals(sentinel, RouteColor.hashColor(null));
    assertEquals(sentinel, RouteColor.hashColor("route"));
  }

  /** A valid 6-hex GTFS colour wins and is normalised to lowercase "#rrggbb". */
  @Test
  void forRoutePrefersValidGtfsColour() {
    assertEquals("#1a2b3c", RouteColor.forRoute("9", "1A2B3C"));
    assertEquals("#ff0000", RouteColor.forRoute("9", " ff0000 "));
  }

  /** Missing/blank/malformed GTFS colours fall back to the hash. */
  @Test
  void forRouteFallsBackOnInvalidGtfsColour() {
    String hashed = RouteColor.hashColor("9");
    assertEquals(hashed, RouteColor.forRoute("9", ""));
    assertEquals(hashed, RouteColor.forRoute("9", null));
    assertEquals(hashed, RouteColor.forRoute("9", "#1a2b3c")); // leading '#' → not 6 bare hex
    assertEquals(hashed, RouteColor.forRoute("9", "12345")); // too short
    assertEquals(hashed, RouteColor.forRoute("9", "gggggg")); // non-hex
  }
}
