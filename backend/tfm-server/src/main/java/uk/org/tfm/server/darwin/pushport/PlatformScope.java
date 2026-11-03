package uk.org.tfm.server.darwin.pushport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Station TIPLOC, CRS, name, and coordinate lookups from the bundled NaPTAN reference. */
@Singleton
public class PlatformScope {

  private static final Logger log = LoggerFactory.getLogger(PlatformScope.class);
  private static final String RESOURCE = "/naptan/rail-references.csv";

  private static final String RAIL_STATION_SUFFIX = " Rail Station";

  private final Set<String> tiplocs;

  /** Upper-cased CRS → TIPLOC, for platform-board lookups. */
  private final Map<String, String> crsToTpl;

  /** Upper-cased CRS → TIPLOC for ALL stations in the CSV (used for GetTrainService lookups). */
  private final Map<String, String> allCrsToTpl;

  /** Upper-cased CRS → display name (suffix stripped). */
  private final Map<String, String> crsToName;

  /** Upper-cased TIPLOC → display name (suffix stripped). */
  private final Map<String, String> tplToName;

  /** Upper-cased TIPLOC → {lat, lng} for ALL stations in the CSV. */
  private final Map<String, double[]> tplToLatLng;

  /** Load the national station reference for platform boards and journey lookups. */
  @Inject
  public PlatformScope() {
    this((Set<String>) null);
  }

  /** {@code inScopeCrs == null} ⇒ every station in the CSV is in scope (national). */
  private PlatformScope(Set<String> inScopeCrs) {
    LoadResult result = load(inScopeCrs);
    this.tiplocs = result.tiplocs();
    this.crsToTpl = result.crsToTpl();
    this.allCrsToTpl = result.allCrsToTpl();
    this.crsToName = result.crsToName();
    this.tplToName = result.tplToName();
    this.tplToLatLng = result.tplToLatLng();
    log.info(
        "Darwin platform scope: {} in-scope TIPLOCs ({}).",
        tiplocs.size(),
        inScopeCrs == null ? "national" : inScopeCrs.size() + " anchor CRS");
  }

  /** Returns {@code true} if the given TIPLOC is in scope for platform tracking. */
  public boolean inScope(String tpl) {
    return tpl != null && tiplocs.contains(tpl);
  }

  /** The full set of in-scope TIPLOCs. */
  public Set<String> tiplocs() {
    return tiplocs;
  }

  /**
   * Returns the TIPLOC for the given CRS code, or {@link Optional#empty()} if unknown. The lookup
   * is case-insensitive on {@code crs}. Used to look up a station's platform board.
   */
  public Optional<String> tplForCrs(String crs) {
    if (crs == null || crs.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(crsToTpl.get(crs.toUpperCase()));
  }

  /**
   * Returns the display name for the given CRS code (trailing " Rail Station" stripped), or {@link
   * Optional#empty()} if unknown. Lookup is case-insensitive.
   */
  public Optional<String> nameForCrs(String crs) {
    if (crs == null || crs.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(crsToName.get(crs.toUpperCase()));
  }

  /**
   * Returns the display name for the given TIPLOC (trailing " Rail Station" stripped), or {@link
   * Optional#empty()} if unknown. Lookup is case-insensitive.
   */
  public Optional<String> nameForTpl(String tpl) {
    if (tpl == null || tpl.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(tplToName.get(tpl.toUpperCase()));
  }

  /**
   * Returns the TIPLOC for the given CRS code across <em>all</em> stations in the NaPTAN CSV (not
   * just the in-scope anchor stations). Used by {@code GetTrainService} to resolve any GB station
   * CRS to its TIPLOC for the timetable stop join. Lookup is case-insensitive.
   */
  public Optional<String> tplForAnyCrs(String crs) {
    if (crs == null || crs.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(allCrsToTpl.get(crs.toUpperCase()));
  }

  /**
   * Returns the WGS-84 coordinates for the given TIPLOC as a two-element array {@code [lat, lng]},
   * or {@link Optional#empty()} if the TIPLOC is not present in the NaPTAN CSV. Lookup is
   * case-insensitive.
   */
  public Optional<double[]> latLngForTpl(String tpl) {
    if (tpl == null || tpl.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(tplToLatLng.get(tpl.toUpperCase()));
  }

  private record LoadResult(
      Set<String> tiplocs,
      Map<String, String> crsToTpl,
      Map<String, String> allCrsToTpl,
      Map<String, String> crsToName,
      Map<String, String> tplToName,
      Map<String, double[]> tplToLatLng) {}

  private static LoadResult load(Set<String> crsCodes) {
    try (InputStream in = PlatformScope.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("NaPTAN rail reference resource not found: " + RESOURCE);
      }
      Set<String> tiplocs = new HashSet<>();
      Map<String, String> crsToTpl = new HashMap<>();
      // allCrsToTpl covers ALL rows — GetTrainService needs to resolve any GB station CRS.
      Map<String, String> allCrsToTpl = new HashMap<>();
      // Name and lat/lng maps cover ALL rows (not just in-scope), so enrichment works for any
      // TIPLOC or CRS that appears in a schedule, not just the home station.
      Map<String, String> crsToName = new HashMap<>();
      Map<String, String> tplToName = new HashMap<>();
      Map<String, double[]> tplToLatLng = new HashMap<>();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        // Skip comment lines (start with '#') and blanks.
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        // Header: crs,tiploc,station_name,lat,lng — skip it too.
        String[] parts = line.split(",", -1);
        if (parts.length < 3) {
          continue;
        }
        String crs = parts[0].trim();
        String tpl = parts[1].trim();
        String rawName = parts[2].trim();
        if (crs.isEmpty() || tpl.isEmpty() || crs.equalsIgnoreCase("crs")) {
          continue; // header row or malformed
        }
        String displayName =
            rawName.endsWith(RAIL_STATION_SUFFIX)
                ? rawName.substring(0, rawName.length() - RAIL_STATION_SUFFIX.length())
                : rawName;
        // Name maps are populated for every row so origin/destination lookups work globally.
        if (!displayName.isEmpty()) {
          crsToName.put(crs.toUpperCase(), displayName);
          tplToName.put(tpl.toUpperCase(), displayName);
        }
        // All-stations CRS→TIPLOC (used by GetTrainService for any GB station).
        allCrsToTpl.put(crs.toUpperCase(), tpl);
        // Lat/lng — present in columns 4 and 5 when the CSV has ≥5 columns.
        if (parts.length >= 5) {
          try {
            double lat = Double.parseDouble(parts[3].trim());
            double lng = Double.parseDouble(parts[4].trim());
            tplToLatLng.put(tpl.toUpperCase(), new double[] {lat, lng});
          } catch (NumberFormatException ignored) {
            // malformed coordinate — skip
          }
        }
        // Scope (tiplocs + crsToTpl): national (crsCodes == null) covers every station; otherwise
        // restricted to the in-scope corridor CRS codes only.
        if (crsCodes == null || crsCodes.contains(crs)) {
          tiplocs.add(tpl);
          crsToTpl.put(crs.toUpperCase(), tpl);
        }
      }
      return new LoadResult(
          Collections.unmodifiableSet(tiplocs),
          Collections.unmodifiableMap(crsToTpl),
          Collections.unmodifiableMap(allCrsToTpl),
          Collections.unmodifiableMap(crsToName),
          Collections.unmodifiableMap(tplToName),
          Collections.unmodifiableMap(tplToLatLng));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load NaPTAN rail references from " + RESOURCE, e);
    }
  }
}
