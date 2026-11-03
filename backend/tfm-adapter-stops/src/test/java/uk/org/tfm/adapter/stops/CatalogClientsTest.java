package uk.org.tfm.adapter.stops;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.org.tfm.model.feed.FeedException;

class CatalogClientsTest {
  @TempDir Path cache;

  private static final String STOP_HEADER =
      "\uFEFFStatus,StopType,Longitude,Latitude,CommonName,ATCOCode,Indicator,LocalityName,ParentLocalityName\n";
  private static final String PLACE_HEADER =
      "Northing,LocalityName,Easting,ParentLocalityName,NptgLocalityCode,QualifierName\n";

  @Test
  void cachedStopsUseHeadersFilterRowsAndDecodeQuotedNames() throws Exception {
    Files.writeString(
        cache.resolve("naptan.csv"),
        STOP_HEADER
            + """
        active,BCT,-0.88,52.77,"Market, \"\"Square\"\"",stop-1,opp,Melton,Leicestershire
        inactive,BCT,-0.88,52.77,Closed,stop-2,,Melton,Leicestershire
        active,AIR,-0.88,52.77,Airport,stop-3,,Melton,Leicestershire
        active,BCT,0,0,Placeholder,stop-4,,Melton,Leicestershire
        active,BCT,-0.88,52.77,,stop-5,,Melton,Leicestershire
        active,BCT,-0.88,invalid,Bad,stop-6,,Melton,Leicestershire
        """);
    var client = new NaptanClient(cache, "");
    var stops = client.fetch();
    assertEquals(1, stops.size());
    var stop = stops.getFirst();
    assertEquals("stop-1", stop.atcoCode());
    assertEquals("Market, \"Square\"", stop.commonName());
    assertEquals("opp", stop.indicator());
    assertEquals("Melton", stop.localityName());
    assertEquals("Leicestershire", stop.parentLocalityName());
    assertEquals(52.77, stop.lat());
    assertEquals(-0.88, stop.lng());
    assertEquals("", stop.crs());
    assertTrue(client.lastLoadedAt().isPresent());
  }

  @Test
  void adapterJarIncludesRailReferencesForStationAndEntranceResolution() throws Exception {
    Files.writeString(
        cache.resolve("naptan.csv"),
        STOP_HEADER
            + """
        active,RLY,-0.88587,52.76103,Melton Mowbray Rail Station,9100MLTNSDG,,Melton,Leicestershire
        active,RSE,-0.88587,52.76103,Unknown entrance,entrance,,Melton,Leicestershire
        active,RLY,-0.88587,52.76103,Heritage,9100UNKNOWN,,Melton,Leicestershire
        """);
    var stops = new NaptanClient(cache, "").fetch();
    assertEquals(List.of("MMO", "MMO", ""), stops.stream().map(s -> s.crs()).toList());
  }

  @Test
  void cachedLocalitiesKeepGridCoordinatesAndRejectInvalidRows() throws Exception {
    Files.writeString(
        cache.resolve("nptg_localities.csv"),
        PLACE_HEADER
            + """
        319000,Melton Mowbray,475000,Leicestershire,N001,Town
        0,Missing coordinates,0,,N002,
        NaN,Bad coordinates,475000,,N003,
        319000,,475000,,N004,
        319000,Missing code,475000,,,
        """);
    var client = new NptgClient(cache, "https://unused.invalid");
    var places = client.fetch();
    assertEquals(1, places.size());
    var place = places.getFirst();
    assertEquals("N001", place.code());
    assertEquals("Melton Mowbray", place.name());
    assertEquals("Town", place.qualifier());
    assertEquals("Leicestershire", place.parentName());
    assertEquals(475000, place.easting());
    assertEquals(319000, place.northing());
    assertTrue(client.lastLoadedAt().isPresent());
  }

  @Test
  void malformedCachedCsvIsReportedAsInvalidResponse() throws Exception {
    Files.writeString(cache.resolve("naptan.csv"), "Wrong,Header\n");
    Files.writeString(cache.resolve("nptg_localities.csv"), "");
    assertEquals(
        FeedException.Kind.INVALID_RESPONSE,
        assertThrows(FeedException.class, () -> new NaptanClient(cache, "").fetch()).kind());
    assertEquals(
        FeedException.Kind.INVALID_RESPONSE,
        assertThrows(
                FeedException.class, () -> new NptgClient(cache, "https://unused.invalid").fetch())
            .kind());
  }

  @Test
  void absentCacheHasNoTimestamp() {
    assertTrue(new NaptanClient(cache, "").lastLoadedAt().isEmpty());
    assertTrue(new NptgClient(cache, "https://unused.invalid").lastLoadedAt().isEmpty());
  }
}
