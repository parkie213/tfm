package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.feed.FeedHealth;
import uk.org.tfm.model.feed.TrainBoardFeeds;
import uk.org.tfm.model.storage.LocalityCountSource;
import uk.org.tfm.model.storage.StopCountSource;
import uk.org.tfm.model.storage.TripCountSource;
import uk.org.tfm.proto.v1.DataSource;
import uk.org.tfm.proto.v1.DataSourceStatus;
import uk.org.tfm.server.config.Config;

/**
 * Unit tests for {@link DataSourceRegistry}. All collaborators are hand-written fakes implemented
 * as lambdas / anonymous classes against the narrow service-info interfaces — no Testcontainers, no
 * DB, no network, no subclassing of final production classes.
 */
class DataSourceRegistryTest {

  // ---- Default fakes for a no-key dev environment ----

  static BusVehicleInfo busDisabled() {
    return new BusVehicleInfo() {
      public boolean isEnabled() {
        return false;
      }

      public Optional<Instant> lastFetchedAt() {
        return Optional.empty();
      }

      public int vehicleCount() {
        return 0;
      }

      public boolean isStale() {
        return true;
      }
    };
  }

  static PlatformFeedInfo platformDisabled() {
    return new PlatformFeedInfo() {
      public boolean isEnabled() {
        return false;
      }

      public int platformIndexSize() {
        return 0;
      }

      public boolean isRunning() {
        return false;
      }

      public Optional<Instant> lastMessageAt() {
        return Optional.empty();
      }
    };
  }

  static GtfsFeedInfo gtfsDisabled() {
    return new GtfsFeedInfo() {
      public boolean isEnabled() {
        return false;
      }

      public List<String> getRegions() {
        return List.of("east_midlands");
      }
    };
  }

  static LoadStateInfo loadStateEmpty() {
    return () -> new LoadStateInfo.FeedState(null, null);
  }

  static TripCountSource noTrips() {
    return () -> 0L;
  }

  static StopCountSource noStops() {
    return () -> 0L;
  }

  static LocalityCountSource noLocalities() {
    return () -> 0L;
  }

  static NaptanLoaderInfo naptanNoTimestamp() {
    return () -> Optional.empty();
  }

  static NptgLoaderInfo nptgNoTimestamp() {
    return () -> Optional.empty();
  }

  static OsrmInfo osrmDisabled() {
    return new OsrmInfo() {
      public boolean enabled() {
        return false;
      }

      public boolean isReachable() {
        return false;
      }
    };
  }

  static OsrmInfo osrmUnreachable() {
    return new OsrmInfo() {
      public boolean enabled() {
        return true;
      }

      public boolean isReachable() {
        return false;
      }
    };
  }

  static FeedHealth ldbwsDisabled() {
    return new FeedHealth() {
      public boolean configured() {
        return false;
      }

      public Optional<Instant> lastSuccessAt() {
        return Optional.empty();
      }

      public Optional<Instant> lastErrorAt() {
        return Optional.empty();
      }

      public Optional<String> lastError() {
        return Optional.empty();
      }
    };
  }

  /** Configured LDBWS whose most recent call failed → ERROR. */
  static FeedHealth ldbwsErrored() {
    return new FeedHealth() {
      public boolean configured() {
        return true;
      }

      public Optional<Instant> lastSuccessAt() {
        return Optional.empty();
      }

      public Optional<Instant> lastErrorAt() {
        return Optional.of(Instant.now());
      }

      public Optional<String> lastError() {
        return Optional.of("HTTP 401: invalid key");
      }
    };
  }

  static TrainBoardFeeds noBoards() {
    return new TrainBoardFeeds(ldbwsDisabled(), ldbwsDisabled());
  }

  /** Configured board feed whose most recent call succeeded → LIVE. */
  static FeedHealth ldbwsLive() {
    return new FeedHealth() {
      public boolean configured() {
        return true;
      }

      public Optional<Instant> lastSuccessAt() {
        return Optional.of(Instant.now());
      }

      public Optional<Instant> lastErrorAt() {
        return Optional.empty();
      }

      public Optional<String> lastError() {
        return Optional.empty();
      }
    };
  }

  /** Enabled push-port consumer whose last message arrived at {@code at}. */
  static PlatformFeedInfo platformLastMessage(Instant at) {
    return new PlatformFeedInfo() {
      public boolean isEnabled() {
        return true;
      }

      public int platformIndexSize() {
        return 10;
      }

      public boolean isRunning() {
        return true;
      }

      public Optional<Instant> lastMessageAt() {
        return Optional.of(at);
      }
    };
  }

  static DataSourceRegistry registryWith(PlatformFeedInfo platform, TrainBoardFeeds boards) {
    return new DataSourceRegistry(
        Config.minimalForTesting(),
        busDisabled(),
        platform,
        gtfsDisabled(),
        loadStateEmpty(),
        noTrips(),
        noStops(),
        noLocalities(),
        naptanNoTimestamp(),
        nptgNoTimestamp(),
        osrmDisabled(),
        boards);
  }

  /** Builds a registry with all defaults (no keys/credentials configured). */
  static DataSourceRegistry defaultRegistry() {
    return new DataSourceRegistry(
        Config.minimalForTesting(),
        busDisabled(),
        platformDisabled(),
        gtfsDisabled(),
        loadStateEmpty(),
        noTrips(),
        noStops(),
        noLocalities(),
        naptanNoTimestamp(),
        nptgNoTimestamp(),
        osrmDisabled(),
        noBoards());
  }

  /** Default registry with specific OSRM / LDBWS / NaPTAN views (others defaulted). */
  static DataSourceRegistry registryWith(OsrmInfo osrm, FeedHealth ldbws, NaptanLoaderInfo naptan) {
    return new DataSourceRegistry(
        Config.minimalForTesting(),
        busDisabled(),
        platformDisabled(),
        gtfsDisabled(),
        loadStateEmpty(),
        noTrips(),
        noStops(),
        noLocalities(),
        naptan,
        nptgNoTimestamp(),
        osrm,
        new TrainBoardFeeds(ldbws, ldbwsDisabled()));
  }

  static DataSource find(DataSourceRegistry r, String id) {
    return r.describe().stream()
        .filter(s -> s.getId().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Source not found: " + id));
  }

  // ---- Tests ----

  @Test
  void bodsDisabled_whenNoKey() {
    // Default registry has no BODS key → DISABLED
    DataSource bods = find(defaultRegistry(), "bods-siri-vm");
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_DISABLED, bods.getStatus());
  }

  @Test
  void bodsError_whenEnabledButStale() {
    BusVehicleInfo staleEnabled =
        new BusVehicleInfo() {
          public boolean isEnabled() {
            return true;
          }

          public Optional<Instant> lastFetchedAt() {
            return Optional.empty();
          } // never fetched

          public int vehicleCount() {
            return 0;
          }

          public boolean isStale() {
            return true;
          } // stale
        };

    DataSourceRegistry r =
        new DataSourceRegistry(
            Config.withBodsKey("test-key"),
            staleEnabled,
            platformDisabled(),
            gtfsDisabled(),
            loadStateEmpty(),
            noTrips(),
            noStops(),
            noLocalities(),
            naptanNoTimestamp(),
            nptgNoTimestamp(),
            osrmDisabled(),
            noBoards());

    DataSource bods = find(r, "bods-siri-vm");
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_ERROR, bods.getStatus());
  }

  @Test
  void bodsLive_whenEnabledAndFresh() {
    Instant fetched = Instant.now().minusSeconds(5);
    BusVehicleInfo live =
        new BusVehicleInfo() {
          public boolean isEnabled() {
            return true;
          }

          public Optional<Instant> lastFetchedAt() {
            return Optional.of(fetched);
          }

          public int vehicleCount() {
            return 77;
          }

          public boolean isStale() {
            return false;
          }
        };

    DataSourceRegistry r =
        new DataSourceRegistry(
            Config.withBodsKey("test-key"),
            live,
            platformDisabled(),
            gtfsDisabled(),
            loadStateEmpty(),
            noTrips(),
            noStops(),
            noLocalities(),
            naptanNoTimestamp(),
            nptgNoTimestamp(),
            osrmDisabled(),
            noBoards());

    DataSource bods = find(r, "bods-siri-vm");
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_LIVE, bods.getStatus());
    assertEquals(fetched.toString(), bods.getLastUpdated());
    assertEquals(77, bods.getItemCount());
  }

  @Test
  void darwinPushport_disabledByDefault_noKafkaCreds() {
    // Default registry: DarwinPlatformService.isEnabled() = false → DISABLED
    DataSource darwin = find(defaultRegistry(), "darwin-pushport");
    assertEquals(
        DataSourceStatus.DATA_SOURCE_STATUS_DISABLED,
        darwin.getStatus(),
        "darwin-pushport should be DISABLED when Kafka credentials are absent");
    assertTrue(
        darwin.getDetail().contains("Kafka"),
        "detail should reference Kafka credentials so operators know how to enable it");
  }

  @Test
  void describeReturnsAllNineSources() {
    List<DataSource> sources = defaultRegistry().describe();
    List<String> ids = sources.stream().map(DataSource::getId).toList();

    for (String expected :
        List.of(
            "rdm-ldbws",
            "rdm-arrivals",
            "darwin-pushport",
            "bods-siri-vm",
            "gtfs-east-midlands",
            "osrm",
            "openfreemap",
            "naptan",
            "nptg")) {
      assertTrue(ids.contains(expected), "missing source: " + expected);
    }
    assertEquals(9, sources.size(), "expected exactly 9 sources");
  }

  @Test
  void basemap_alwaysLive() {
    DataSource src = find(defaultRegistry(), "openfreemap");
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_LIVE, src.getStatus());
    assertEquals(0, src.getExpectedIntervalSecs());
    assertTrue(src.getDetail().contains("OpenStreetMap"));
  }

  @Test
  void naptanLastUpdated_reflectsLoaderTimestamp_andStopCount() {
    Instant csvMtime = Instant.parse("2025-06-01T03:00:00Z");
    NaptanLoaderInfo naptan = () -> Optional.of(csvMtime);
    StopCountSource stops = () -> 55_000L;

    DataSourceRegistry r =
        new DataSourceRegistry(
            Config.minimalForTesting(),
            busDisabled(),
            platformDisabled(),
            gtfsDisabled(),
            loadStateEmpty(),
            noTrips(),
            stops,
            noLocalities(),
            naptan,
            nptgNoTimestamp(),
            osrmDisabled(),
            noBoards());

    DataSource naptanSrc = find(r, "naptan");
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_LIVE, naptanSrc.getStatus());
    assertEquals(csvMtime.toString(), naptanSrc.getLastUpdated());
    assertEquals(55_000L, naptanSrc.getItemCount());
    assertEquals(86400, naptanSrc.getExpectedIntervalSecs());
  }

  @Test
  void naptan_scaffoldedUntilLoaded() {
    DataSource src = find(defaultRegistry(), "naptan");
    assertEquals(
        DataSourceStatus.DATA_SOURCE_STATUS_SCAFFOLDED,
        src.getStatus(),
        "NaPTAN should not claim LIVE before it has actually loaded");
  }

  @Test
  void rdmLdbws_disabledWithoutKey() {
    DataSource src = find(defaultRegistry(), "rdm-ldbws");
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_DISABLED, src.getStatus());
  }

  @Test
  void rdmLdbws_errorWhenLastFetchFailed() {
    DataSource src =
        find(registryWith(osrmDisabled(), ldbwsErrored(), naptanNoTimestamp()), "rdm-ldbws");
    assertEquals(
        DataSourceStatus.DATA_SOURCE_STATUS_ERROR,
        src.getStatus(),
        "a failed last LDBWS call must surface as ERROR, not LIVE");
    assertTrue(src.getDetail().contains("401"), "detail should carry the failure reason");
    assertEquals(0, src.getExpectedIntervalSecs(), "on-demand API has no cadence");
  }

  @Test
  void osrm_errorWhenUnreachable() {
    DataSource src =
        find(registryWith(osrmUnreachable(), ldbwsDisabled(), naptanNoTimestamp()), "osrm");
    assertEquals(
        DataSourceStatus.DATA_SOURCE_STATUS_ERROR,
        src.getStatus(),
        "configured-but-unreachable OSRM must be ERROR, not LIVE");
    assertTrue(src.getDetail().contains("not reachable"));
  }

  @Test
  void darwinPushport_liveWhileMessagesFlow() {
    DataSource src =
        find(
            registryWith(platformLastMessage(Instant.now().minusSeconds(5)), noBoards()),
            "darwin-pushport");
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_LIVE, src.getStatus());
  }

  @Test
  void darwinPushport_errorWhenConsumerHasGoneSilent() {
    Instant last = Instant.now().minus(DataSourceRegistry.PUSHPORT_STALE_AFTER).minusSeconds(60);
    DataSource src = find(registryWith(platformLastMessage(last), noBoards()), "darwin-pushport");
    assertEquals(
        DataSourceStatus.DATA_SOURCE_STATUS_ERROR,
        src.getStatus(),
        "an enabled consumer with no recent messages has stopped and must not read LIVE");
    assertEquals(last.toString(), src.getLastUpdated());
  }

  @Test
  void rdmArrivals_disabledWhenNotSubscribed() {
    DataSource src = find(defaultRegistry(), "rdm-arrivals");
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_DISABLED, src.getStatus());
    assertTrue(src.getDetail().contains("DARWIN_ARRIVALS_BASE_URL"));
  }

  @Test
  void rdmArrivals_failureDoesNotMarkDeparturesBroken() {
    DataSourceRegistry r =
        registryWith(platformDisabled(), new TrainBoardFeeds(ldbwsLive(), ldbwsErrored()));
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_LIVE, find(r, "rdm-ldbws").getStatus());
    assertEquals(DataSourceStatus.DATA_SOURCE_STATUS_ERROR, find(r, "rdm-arrivals").getStatus());
  }
}
