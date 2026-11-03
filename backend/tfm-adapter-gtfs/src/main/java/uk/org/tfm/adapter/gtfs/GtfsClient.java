package uk.org.tfm.adapter.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.timetable.TimetableProvider;
import uk.org.tfm.model.timetable.TimetableSnapshot;
import uk.org.tfm.model.timetable.TimetableSource;

/** Downloads BODS regional GTFS archives and exposes lazily decoded, versioned timetables. */
public final class GtfsClient implements TimetableProvider {
  private static final Logger log = LoggerFactory.getLogger(GtfsClient.class);
  private static final String GTFS_BASE_URL =
      "https://data.bus-data.dft.gov.uk/timetable/download/gtfs-file/";
  private static final Duration CACHE_MAX_AGE = Duration.ofHours(24);
  private final Path cacheDir;
  private final List<String> regions;
  private final boolean loadShapes;
  private final HttpClient http;

  public GtfsClient(Path cacheDir, String regions, boolean loadShapes) {
    this.cacheDir = cacheDir;
    this.regions = List.copyOf(parseRegions(regions));
    this.loadShapes = loadShapes;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  @Override
  public List<String> regions() {
    return regions;
  }

  @Override
  public TimetableSnapshot fetch() throws FeedException {
    List<Path> zips = new ArrayList<>();
    final String version;
    try {
      for (String region : regions) zips.add(ensureCached(region));
      version = computeFeedVersion(zips);
    } catch (IOException e) {
      throw new FeedException("GTFS download/cache failed", FeedException.Kind.UNAVAILABLE, e);
    }
    return new TimetableSnapshot() {
      public String version() {
        return version;
      }

      public TimetableSource read() {
        return new GtfsTimetableSource(zips, loadShapes);
      }
    };
  }

  private Path ensureCached(String region) throws IOException {
    Files.createDirectories(cacheDir);
    String zipName = region + "-gtfs.zip";
    Path zipPath = cacheDir.resolve(zipName);
    if (Files.exists(zipPath)) {
      Instant lastModified = Files.getLastModifiedTime(zipPath).toInstant();
      if (Duration.between(lastModified, Instant.now()).compareTo(CACHE_MAX_AGE) < 0) {
        log.info("GTFS cache fresh for region '{}' ({}), skipping download.", region, zipPath);
        return zipPath;
      }
      log.info("GTFS cache stale for region '{}', re-downloading.", region);
    } else {
      log.info("GTFS zip for region '{}' not cached at {}, downloading.", region, zipPath);
    }
    download(region, zipPath);
    return zipPath;
  }

  private void download(String region, Path dest) throws IOException {
    String url = GTFS_BASE_URL + region;
    Path tmp = dest.resolveSibling(region + "-gtfs.zip.tmp");
    log.info("Downloading GTFS zip for region '{}' from {} ...", region, url);
    long startMs = System.currentTimeMillis();

    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(15)).GET().build();

    HttpResponse<Path> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("GTFS download interrupted for region " + region, e);
    } catch (Exception e) {
      throw new IOException(
          "GTFS download failed for region " + region + ": " + e.getClass().getSimpleName(), e);
    }

    if (resp.statusCode() != 200) {
      Files.deleteIfExists(tmp);
      throw new IOException(
          "GTFS download returned HTTP " + resp.statusCode() + " for region " + region);
    }

    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    long elapsedMs = System.currentTimeMillis() - startMs;
    long sizeMb = Files.size(dest) / (1024 * 1024);
    log.info(
        "GTFS zip downloaded for region '{}': {}MB in {}ms -> {}", region, sizeMb, elapsedMs, dest);
  }

  private String computeFeedVersion(List<Path> zips) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      for (Path zip : zips) {
        try (InputStream in = Files.newInputStream(zip)) {
          int r;
          while ((r = in.read(buf)) > 0) {
            md.update(buf, 0, r);
          }
        }
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static List<String> parseRegions(String cfg) {
    List<String> out = new ArrayList<>();
    for (String r : cfg.split(",")) {
      String trimmed = r.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    if (out.isEmpty()) out.add("east_midlands");
    return out;
  }
}
