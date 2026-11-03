package uk.org.tfm.server.config;

/** Immutable runtime configuration, sourced from environment variables. */
public final class Config {

  private final int grpcPort;
  private final String darwinBaseUrl;
  private final String darwinToken;
  private final String darwinArrivalsBaseUrl;
  private final String darwinArrivalsToken;
  private final int darwinTimeoutMs;
  private final String gtfsDbPath;
  private final String bodsApiKey;
  private final String bodsBbox;
  private final int bodsPollSeconds;
  private final int bodsStalenessSeconds;
  private final String gtfsCacheDir;
  private final boolean busTimetableEnabled;
  private final String darwinKafkaBootstrap;
  private final String darwinKafkaTopic;
  private final String darwinKafkaKey;
  private final String darwinKafkaSecret;
  private final String darwinKafkaGroup;

  private Config(
      int grpcPort,
      String darwinBaseUrl,
      String darwinToken,
      String darwinArrivalsBaseUrl,
      String darwinArrivalsToken,
      int darwinTimeoutMs,
      String gtfsDbPath,
      String bodsApiKey,
      String bodsBbox,
      int bodsPollSeconds,
      int bodsStalenessSeconds,
      String gtfsCacheDir,
      boolean busTimetableEnabled,
      String darwinKafkaBootstrap,
      String darwinKafkaTopic,
      String darwinKafkaKey,
      String darwinKafkaSecret,
      String darwinKafkaGroup) {
    this.grpcPort = grpcPort;
    this.darwinBaseUrl = darwinBaseUrl;
    this.darwinToken = darwinToken;
    this.darwinArrivalsBaseUrl = darwinArrivalsBaseUrl;
    this.darwinArrivalsToken = darwinArrivalsToken;
    this.darwinTimeoutMs = darwinTimeoutMs;
    this.gtfsDbPath = gtfsDbPath;
    this.bodsApiKey = bodsApiKey;
    this.bodsBbox = bodsBbox;
    this.bodsPollSeconds = bodsPollSeconds;
    this.bodsStalenessSeconds = bodsStalenessSeconds;
    this.gtfsCacheDir = gtfsCacheDir;
    this.busTimetableEnabled = busTimetableEnabled;
    this.darwinKafkaBootstrap = darwinKafkaBootstrap;
    this.darwinKafkaTopic = darwinKafkaTopic;
    this.darwinKafkaKey = darwinKafkaKey;
    this.darwinKafkaSecret = darwinKafkaSecret;
    this.darwinKafkaGroup = darwinKafkaGroup;
  }

  public static Config fromEnv() {
    String darwinToken = env("DARWIN_TOKEN", "");
    return new Config(
        intEnv("GRPC_PORT", 9090),
        env(
            "DARWIN_BASE_URL",
            "https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS/api/20220120"),
        darwinToken,
        // Arrivals come from a separate RDM product; empty until the user subscribes.
        env("DARWIN_ARRIVALS_BASE_URL", ""),
        // Falls back to the departures key if the same RDM key covers both products.
        env("DARWIN_ARRIVALS_TOKEN", darwinToken),
        intEnv("DARWIN_TIMEOUT_MS", 10_000),
        env("GTFS_DB_PATH", "/data/gtfs.db"),
        env("BODS_API_KEY", ""),
        // SIRI-VM bounding box: Leicester city east to the Melton Mowbray + Oakham corridor (incl.
        // approaches like Asfordby/Frisby/Waltham). minLng,minLat,maxLng,maxLat.
        env("BODS_BBOX", "-1.25,52.55,-0.65,52.90"),
        intEnv("BODS_POLL_SECONDS", 10),
        intEnv("BODS_STALENESS_SECONDS", 180),
        // GTFS bulk timetable (open data, no API key required).
        env("GTFS_CACHE_DIR", "/data"),
        boolEnv("BUS_TIMETABLE_ENABLED", true),
        // Darwin Real Time push port (RDM Kafka). Credentials are the consumer key/secret from the
        // RDM "Darwin Real Time Train Information" product. Empty bootstrap/key/secret disables the
        // feed.
        env("DARWIN_KAFKA_BOOTSTRAP", ""),
        env("DARWIN_KAFKA_TOPIC", ""),
        env("DARWIN_KAFKA_KEY", ""),
        env("DARWIN_KAFKA_SECRET", ""),
        env("DARWIN_KAFKA_GROUP", "tfm-darwin-platform"));
  }

  public int grpcPort() {
    return grpcPort;
  }

  /** Base URL of the RDM LDBWS REST product (no trailing slash needed). */
  public String darwinBaseUrl() {
    return darwinBaseUrl;
  }

  /** RDM consumer key, sent as the {@code x-apikey} header. */
  public String darwinToken() {
    return darwinToken;
  }

  /** Base URL of the RDM arrivals product; empty if arrivals are not enabled. */
  public String darwinArrivalsBaseUrl() {
    return darwinArrivalsBaseUrl;
  }

  /** Consumer key for the arrivals product (defaults to {@link #darwinToken()}). */
  public String darwinArrivalsToken() {
    return darwinArrivalsToken;
  }

  public boolean arrivalsEnabled() {
    return !darwinArrivalsBaseUrl.isBlank();
  }

  public int darwinTimeoutMs() {
    return darwinTimeoutMs;
  }

  public String gtfsDbPath() {
    return gtfsDbPath;
  }

  public String bodsApiKey() {
    return bodsApiKey;
  }

  public boolean busVehiclesEnabled() {
    return !bodsApiKey.isBlank();
  }

  /** SIRI-VM bounding box as minLng,minLat,maxLng,maxLat. */
  public String bodsBbox() {
    return bodsBbox;
  }

  public int bodsPollSeconds() {
    return bodsPollSeconds;
  }

  public int bodsStalenessSeconds() {
    return bodsStalenessSeconds;
  }

  /** Directory where the GTFS zip is cached on disk. */
  public String gtfsCacheDir() {
    return gtfsCacheDir;
  }

  /** Whether the GTFS scheduled bus timetable feature is enabled. */
  public boolean busTimetableEnabled() {
    return busTimetableEnabled;
  }

  /** Bootstrap server(s) for the RDM Darwin Real Time Kafka feed. */
  public String darwinKafkaBootstrap() {
    return darwinKafkaBootstrap;
  }

  /** Kafka topic for the RDM Darwin Real Time push port (issued per-subscription). */
  public String darwinKafkaTopic() {
    return darwinKafkaTopic;
  }

  /** RDM consumer key (Kafka username); never logged. */
  public String darwinKafkaKey() {
    return darwinKafkaKey;
  }

  /** RDM consumer secret (Kafka password); never logged. */
  public String darwinKafkaSecret() {
    return darwinKafkaSecret;
  }

  /** Kafka consumer group ID for the Darwin platform feed. */
  public String darwinKafkaGroup() {
    return darwinKafkaGroup;
  }

  /**
   * Whether the Darwin platform feed is enabled: credentials present (bootstrap, key, secret all
   * non-blank) AND the {@code DARWIN_KAFKA_ENABLED} kill-switch is not set to false. The switch
   * lets an environment release the single-partition consumer group (only one consumer may hold it)
   * while keeping the rest of the server running — see {@code deploy/darwin-feed.sh}.
   */
  public boolean platformFeedEnabled() {
    return !darwinKafkaBootstrap.isBlank()
        && !darwinKafkaKey.isBlank()
        && !darwinKafkaSecret.isBlank()
        && boolEnv("DARWIN_KAFKA_ENABLED", true);
  }

  // ---- Postgres/PostGIS (national stops + GTFS). Read directly from env. ----
  /** JDBC URL for the Postgres+PostGIS database. */
  public String dbUrl() {
    return env("DB_URL", "jdbc:postgresql://localhost:5432/tfm");
  }

  public String dbUser() {
    return env("DB_USER", "tfm");
  }

  public String dbPassword() {
    return env("DB_PASSWORD", "tfm");
  }

  /** Comma-separated BODS GTFS regions to load (e.g. "east_midlands,london"). */
  public String gtfsRegions() {
    return env("GTFS_REGIONS", "east_midlands");
  }

  /** Comma-separated NaPTAN ATCO area codes to load; blank = national (all GB). */
  public String naptanAreas() {
    return env("NAPTAN_AREAS", "");
  }

  /** NPTG localities CSV URL (the DfT gazetteer of GB places, searched as "place" results). */
  public String nptgLocalitiesUrl() {
    return env("NPTG_URL", "https://naptan.api.dft.gov.uk/v1/nptg/localities?dataFormat=csv");
  }

  /**
   * Load GTFS route geometry (shapes.txt) for accurate route lines. On for regional, off national.
   */
  public boolean gtfsLoadShapes() {
    return boolEnv("GTFS_LOAD_SHAPES", true);
  }

  /** OSRM base URL for road-snapping routes that lack a GTFS shape; blank disables the fallback. */
  public String osrmUrl() {
    return env("OSRM_URL", "");
  }

  /**
   * Identifier for the OSRM road network the routes are snapped against — part of the geometry
   * freshness key, so bumping it forces a one-off re-derive after you rebuild the OSRM extract.
   * Blank by default (a stable empty component); set it (e.g. to the .osm.pbf date/hash) when you
   * replace an externally managed OSRM dataset so the next load re-snaps.
   */
  public String osrmBuildId() {
    return env("OSRM_VERSION", "");
  }

  // ---- Test factories (never read environment variables) ----

  /**
   * Returns a minimal Config with safe default values for every field. Intended for use in unit
   * tests that need a real Config instance but do not require any specific configuration. Never
   * reads environment variables.
   */
  public static Config minimalForTesting() {
    return new Config(
        /* grpcPort */ 9090,
        /* darwinBaseUrl */ "",
        /* darwinToken */ "",
        /* darwinArrivalsBaseUrl */ "",
        /* darwinArrivalsToken */ "",
        /* darwinTimeoutMs */ 5000,
        /* gtfsDbPath */ "",
        /* bodsApiKey */ "",
        /* bodsBbox */ "",
        /* bodsPollSeconds */ 10,
        /* bodsStalenessSeconds */ 180,
        /* gtfsCacheDir */ "/tmp",
        /* busTimetableEnabled */ false,
        /* darwinKafkaBootstrap */ "",
        /* darwinKafkaTopic */ "",
        /* darwinKafkaKey */ "",
        /* darwinKafkaSecret */ "",
        /* darwinKafkaGroup */ "tfm-test");
  }

  /** Test factory: minimal config with a non-blank BODS API key (enables bus vehicles). */
  public static Config withBodsKey(String key) {
    return new Config(
        9090,
        "",
        "",
        "",
        "",
        5000,
        "",
        key,
        "",
        10,
        180,
        "/tmp",
        false,
        "",
        "",
        "",
        "",
        "tfm-test");
  }

  /** Test factory: minimal config with a non-blank Darwin LDBWS token. */
  public static Config withDarwinToken(String token) {
    return new Config(
        9090,
        "",
        token,
        "",
        "",
        5000,
        "",
        "",
        "",
        10,
        180,
        "/tmp",
        false,
        "",
        "",
        "",
        "",
        "tfm-test");
  }

  private static String env(String key, String dflt) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? dflt : v.trim();
  }

  private static int intEnv(String key, int dflt) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) return dflt;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  private static boolean boolEnv(String key, boolean dflt) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) return dflt;
    return !"false".equalsIgnoreCase(v.trim()) && !"0".equals(v.trim());
  }
}
