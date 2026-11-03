package uk.org.tfm.server.gtfs;

import java.util.Locale;

/**
 * Resolves the display colour for a bus route — the single source of truth, replacing the old
 * client-side hash. {@link #forRoute} prefers the official GTFS {@code route_color}; where the feed
 * omits it (common for UK BODS feeds) it falls back to {@link #hashColor}, a byte-for-byte port of
 * the frontend's former djb2 → HSL hash, so a route with no official colour keeps the exact colour
 * it had before this moved server-side. The result is a ready-to-paint CSS colour string.
 */
public final class RouteColor {
  private RouteColor() {}

  /**
   * Final CSS colour for a route: the GTFS {@code route_color} as {@code "#rrggbb"} when it is a
   * valid 6-digit hex, else the deterministic {@link #hashColor} fallback for {@code shortName}.
   */
  public static String forRoute(String shortName, String gtfsColor) {
    if (gtfsColor != null) {
      String hex = gtfsColor.trim();
      if (hex.length() == 6 && isHex(hex)) {
        return "#" + hex.toLowerCase(Locale.ROOT);
      }
    }
    return hashColor(shortName);
  }

  /**
   * Deterministic, readable {@code hsl(h, 70%, 42%)} colour hashed from a route short name. Ported
   * exactly from the frontend (djb2 with JS Number semantics: each step is ToInt32 of {@code h*33}
   * XOR the UTF-16 code unit, then {@code >>> 0} before the modulo) so colours are unchanged.
   */
  public static String hashColor(String shortName) {
    String key = shortName == null ? "" : shortName.trim();
    if (key.isEmpty()) {
      key = "route";
    }
    int h = 5381;
    for (int i = 0; i < key.length(); i++) {
      h = (int) (h * 33L) ^ key.charAt(i); // long multiply then narrow to int32 == JS ToInt32(h*33)
    }
    long u = h & 0xffffffffL; // >>> 0
    long hue = u % 360;
    return "hsl(" + hue + ", 70%, 42%)";
  }

  private static boolean isHex(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) {
        return false;
      }
    }
    return true;
  }
}
