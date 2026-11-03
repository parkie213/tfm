package uk.org.tfm.adapter.gtfs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Stateless CSV, service-day time, and archive readers for the GTFS adapter. */
final class GtfsLoader {

  private GtfsLoader() {}

  // ---- CSV parsing ----

  /**
   * Minimal GTFS-safe CSV line splitter. GTFS fields are either unquoted or double-quoted with
   * possible embedded commas. This handles the common cases in BODS data without pulling in a full
   * CSV library.
   */
  static String[] parseCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    int len = line.length();
    int i = 0;
    while (i <= len) {
      if (i == len) {
        fields.add("");
        break;
      }
      if (line.charAt(i) == '"') {
        // Quoted field: scan to closing quote (handle "" escapes)
        StringBuilder sb = new StringBuilder();
        i++; // skip opening quote
        while (i < len) {
          char c = line.charAt(i);
          if (c == '"') {
            if (i + 1 < len && line.charAt(i + 1) == '"') {
              sb.append('"');
              i += 2;
            } else {
              i++; // skip closing quote
              break;
            }
          } else {
            sb.append(c);
            i++;
          }
        }
        fields.add(sb.toString());
        // skip delimiter or end
        if (i < len && line.charAt(i) == ',') i++;
      } else {
        // Unquoted field: scan to next comma
        int start = i;
        while (i < len && line.charAt(i) != ',') i++;
        fields.add(line.substring(start, i));
        if (i < len) i++; // skip comma
      }
    }
    return fields.toArray(new String[0]);
  }

  /**
   * Parses a GTFS time string like "08:15:00" or "25:03:00" (after-midnight) into seconds since
   * midnight. Returns -1 on parse failure.
   */
  static int parseGtfsTime(String s) {
    // Format is always HH:MM:SS per GTFS spec, but hours can be >= 24.
    int len = s.length();
    if (len < 7) return -1;
    int colonA = s.indexOf(':');
    if (colonA < 0) return -1;
    int colonB = s.indexOf(':', colonA + 1);
    if (colonB < 0) return -1;
    try {
      int h = Integer.parseInt(s, 0, colonA, 10);
      int m = Integer.parseInt(s, colonA + 1, colonB, 10);
      int sec = Integer.parseInt(s, colonB + 1, len, 10);
      return h * 3600 + m * 60 + sec;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Strips surrounding double-quotes and trims whitespace from a CSV field. Does not unescape
   * internal {@code ""} sequences (already handled by {@link #parseCsvLine}).
   */
  static String unquote(String s) {
    String t = s.trim();
    if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
      return t.substring(1, t.length() - 1).trim();
    }
    return t;
  }

  /**
   * Finds the column indices for the named headers in a comma-separated header line. Returns the
   * indices in the same order as {@code names}. Throws {@link IOException} if any name is missing.
   */
  static int[] indices(String headerLine, String... names) throws IOException {
    String[] cols = headerLine.split(",");
    for (int i = 0; i < cols.length; i++) {
      cols[i] = cols[i].trim().replace("﻿", ""); // strip BOM if present
    }
    int[] out = new int[names.length];
    for (int n = 0; n < names.length; n++) {
      out[n] = -1;
      for (int c = 0; c < cols.length; c++) {
        if (names[n].equals(cols[c])) {
          out[n] = c;
          break;
        }
      }
      if (out[n] < 0) {
        throw new IOException(
            "GTFS header missing column '" + names[n] + "'; found: " + headerLine);
      }
    }
    return out;
  }

  /**
   * Column index of {@code name}, or -1 if absent — for OPTIONAL GTFS columns (e.g. route_color).
   */
  static int optionalIndex(String headerLine, String name) {
    String[] cols = headerLine.split(",");
    for (int c = 0; c < cols.length; c++) {
      if (name.equals(cols[c].trim().replace("﻿", ""))) { // strip BOM if present
        return c;
      }
    }
    return -1;
  }

  // ---- ZipFile helpers ----

  static BufferedReader readerFor(ZipFile zf, String entryName) throws IOException {
    ZipEntry entry = zf.getEntry(entryName);
    if (entry == null) {
      throw new IOException("GTFS zip missing entry: " + entryName);
    }
    return reader(zf.getInputStream(entry));
  }

  static BufferedReader reader(InputStream in) {
    return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 128 * 1024);
  }
}
