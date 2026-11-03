package uk.org.tfm.adapter.stops;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.catalog.CatalogProvider;
import uk.org.tfm.model.catalog.LocalityDefinition;
import uk.org.tfm.model.feed.FeedException;

/** Downloads and parses the NPTG locality catalog, with a daily source cache. */
public final class NptgClient implements CatalogProvider<LocalityDefinition> {

  private static final Logger log = LoggerFactory.getLogger(NptgClient.class);

  private static final String CSV_NAME = "nptg_localities.csv";
  private static final Duration CACHE_MAX_AGE = Duration.ofHours(24);

  /** GB National Grid sanity bounds (metres) — rejects blank/garbage easting/northing. */
  private static final double GRID_MIN = 0;

  private static final double EASTING_MAX = 800_000;
  private static final double NORTHING_MAX = 1_400_000;

  private final Path cacheDir;
  private final String url;
  private final HttpClient http;

  public NptgClient(Path cacheDir, String url) {
    this.cacheDir = cacheDir;
    this.url = url;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  // ---- Source cache timestamp ----

  /**
   * The last-modified time of the cached NPTG CSV ({@code nptg_localities.csv}) in the configured
   * cache directory, or empty if the file does not exist or cannot be stat'd. Never throws.
   */
  public Optional<Instant> lastLoadedAt() {
    try {
      Path csvPath = cacheDir.resolve(CSV_NAME);
      if (!Files.exists(csvPath)) {
        return Optional.empty();
      }
      return Optional.of(Files.getLastModifiedTime(csvPath).toInstant());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  @Override
  public List<LocalityDefinition> fetch() throws FeedException {
    Path csvPath = cacheDir.resolve(CSV_NAME);
    try {
      ensureCached(csvPath);
    } catch (IOException e) {
      throw new FeedException("Catalog download/cache failed", FeedException.Kind.UNAVAILABLE, e);
    }
    try {
      return parseCsv(csvPath);
    } catch (IOException e) {
      throw new FeedException("Catalog CSV parse failed", FeedException.Kind.INVALID_RESPONSE, e);
    }
  }

  // ---- Download ----

  private void ensureCached(Path csvPath) throws IOException {
    if (Files.exists(csvPath)) {
      Instant lastModified = Files.getLastModifiedTime(csvPath).toInstant();
      if (Duration.between(lastModified, Instant.now()).compareTo(CACHE_MAX_AGE) < 0) {
        log.info("NPTG cache is fresh ({}), skipping download.", csvPath);
        return;
      }
      log.info("NPTG cache is stale (>{}h), re-downloading.", CACHE_MAX_AGE.toHours());
    } else {
      log.info("NPTG CSV not cached at {}, downloading.", csvPath);
    }
    Files.createDirectories(cacheDir);
    download(csvPath);
  }

  private void download(Path dest) throws IOException {
    Path tmp = dest.resolveSibling(CSV_NAME + ".tmp");
    log.info("Downloading NPTG localities CSV from {} ...", url);
    long startMs = System.currentTimeMillis();

    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(5)) // ~5MB
            .GET()
            .build();

    HttpResponse<Path> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("NPTG download interrupted", e);
    } catch (Exception e) {
      throw new IOException("NPTG download failed: " + e.getClass().getSimpleName(), e);
    }

    if (resp.statusCode() != 200) {
      Files.deleteIfExists(tmp);
      throw new IOException("NPTG download returned HTTP " + resp.statusCode());
    }

    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    long elapsedMs = System.currentTimeMillis() - startMs;
    long sizeKb = Files.size(dest) / 1024;
    log.info("NPTG CSV downloaded: {}KB in {}ms -> {}", sizeKb, elapsedMs, dest);
  }

  // ---- Parse ----

  /**
   * Parses the NPTG localities CSV by header name. Keeps rows with a non-blank code/name and
   * parseable in-range British National Grid easting/northing. Reuses the well-tested quote-aware
   * splitter from {@link NaptanClient}.
   */
  static List<LocalityDefinition> parseCsv(Path csvPath) throws IOException {
    List<LocalityDefinition> out = new ArrayList<>(45_000);

    try (BufferedReader br =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(csvPath), StandardCharsets.UTF_8),
            256 * 1024)) {

      String headerLine = br.readLine();
      if (headerLine == null) {
        throw new IOException("NPTG CSV is empty");
      }
      int[] idx =
          findIndices(
              headerLine,
              "NptgLocalityCode",
              "LocalityName",
              "QualifierName",
              "ParentLocalityName",
              "Easting",
              "Northing");
      int iCode = idx[0];
      int iName = idx[1];
      int iQualifier = idx[2];
      int iParent = idx[3];
      int iEasting = idx[4];
      int iNorthing = idx[5];

      String line;
      while ((line = br.readLine()) != null) {
        if (line.isBlank()) continue;
        String[] fields = NaptanClient.parseCsvLine(line);
        int maxNeeded =
            Math.max(
                Math.max(iCode, iName),
                Math.max(Math.max(iQualifier, iParent), Math.max(iEasting, iNorthing)));
        if (fields.length <= maxNeeded) continue;

        double easting;
        double northing;
        try {
          easting = Double.parseDouble(fields[iEasting].trim());
          northing = Double.parseDouble(fields[iNorthing].trim());
        } catch (NumberFormatException e) {
          continue;
        }
        if (!inGbGrid(easting, northing)) continue;

        String code = fields[iCode].trim();
        String name = fields[iName].trim();
        if (code.isEmpty() || name.isEmpty()) continue;

        out.add(
            new LocalityDefinition(
                code, name, fields[iQualifier].trim(), fields[iParent].trim(), easting, northing));
      }
    }
    return out;
  }

  private static boolean inGbGrid(double easting, double northing) {
    return easting > GRID_MIN
        && easting < EASTING_MAX
        && northing > GRID_MIN
        && northing < NORTHING_MAX;
  }

  private static int[] findIndices(String headerLine, String... names) throws IOException {
    String[] cols = NaptanClient.parseCsvLine(headerLine);
    for (int i = 0; i < cols.length; i++) {
      cols[i] = cols[i].trim().replace("﻿", ""); // strip BOM
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
            "NPTG CSV header missing column '" + names[n] + "'; found: " + headerLine);
      }
    }
    return out;
  }
}
