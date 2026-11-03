package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import uk.org.tfm.model.catalog.Locality;
import uk.org.tfm.model.catalog.Stop;

class CatalogProtoMapperTest {
  @Test
  void mapsStopIdentityLabelsAndCoordinates() {
    var stop =
        CatalogProtoMapper.toProto(
            new Stop("stop-1", "Station", "entrance", "Melton", "RLY", 52.76, -0.88, "MMO"));
    assertEquals("stop-1", stop.getAtcoCode());
    assertEquals("Station", stop.getName());
    assertEquals("entrance", stop.getIndicator());
    assertEquals("Melton", stop.getLocality());
    assertEquals("RLY", stop.getStopType());
    assertEquals(52.76, stop.getLat());
    assertEquals(-0.88, stop.getLng());
    assertEquals("MMO", stop.getCrs());
  }

  @Test
  void mapsPlaceAndPreservesMissingQualifier() {
    var place = CatalogProtoMapper.toProto(new Locality("place-1", "Melton", "", 52.77, -0.88));
    assertEquals("place-1", place.getCode());
    assertEquals("Melton", place.getName());
    assertEquals("", place.getQualifier());
    assertEquals(52.77, place.getLat());
    assertEquals(-0.88, place.getLng());
  }
}
