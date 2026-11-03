package uk.org.tfm.adapter.bods;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.vehicle.VehiclePosition;

class SiriVmParserTest {
  private static List<VehiclePosition> parse(String xml) throws FeedException {
    return SiriVmParser.parse(xml.getBytes(StandardCharsets.UTF_8));
  }

  private static String feed(String activities) {
    return "<Siri xmlns=\"http://www.siri.org.uk/siri\"><ServiceDelivery>"
        + "<VehicleMonitoringDelivery>"
        + activities
        + "</VehicleMonitoringDelivery></ServiceDelivery></Siri>";
  }

  private static String activity(String fields) {
    return "<VehicleActivity><RecordedAtTime>2026-07-10T08:20:00Z</RecordedAtTime>"
        + "<ItemIdentifier>fallback-id</ItemIdentifier><MonitoredVehicleJourney>"
        + "<VehicleLocation><Latitude>52.76</Latitude><Longitude>-0.88</Longitude></VehicleLocation>"
        + fields
        + "</MonitoredVehicleJourney></VehicleActivity>";
  }

  @Test
  void decodesVehicleAndConvertsJourneyIdentityToLocalTime() throws Exception {
    var position =
        parse(
                feed(
                    activity(
                        """
        <VehicleRef>bus-1</VehicleRef><PublishedLineName>19</PublishedLineName>
        <OperatorRef>OP</OperatorRef><DestinationName>Melton_  Mowbray</DestinationName>
        <Bearing>361.4</Bearing><OriginRef>origin-stop</OriginRef>
        <OriginAimedDepartureTime>2026-07-10T08:15:00Z</OriginAimedDepartureTime>
        <FramedVehicleJourneyRef><DataFrameRef>2026-07-10</DataFrameRef></FramedVehicleJourneyRef>
        """)))
            .getFirst();
    assertEquals("bus-1", position.id());
    assertEquals("19", position.line());
    assertEquals("OP", position.operator());
    assertEquals("Melton Mowbray", position.destination());
    assertEquals(52.76, position.lat());
    assertEquals(-0.88, position.lng());
    assertEquals(1, position.bearing());
    assertEquals(Instant.parse("2026-07-10T08:20:00Z"), position.recordedAt());
    assertEquals("origin-stop", position.originRef());
    assertEquals(9 * 3600 + 15 * 60, position.originDepartureSecsLocal());
    assertEquals("2026-07-10", position.serviceDate());
  }

  @Test
  void missingOptionalFieldsKeepFallbackIdentityAndUnknownValues() throws Exception {
    var position = parse(feed(activity("<LineRef>fallback-line</LineRef>"))).getFirst();
    assertEquals("fallback-id", position.id());
    assertEquals("fallback-line", position.line());
    assertEquals(-1, position.bearing());
    assertEquals(-1, position.originDepartureSecsLocal());
    assertEquals("", position.originRef());
    assertEquals("", position.serviceDate());
  }

  @Test
  void derivesServiceDayFromOriginDepartureIncludingLocalMidnight() throws Exception {
    var position =
        parse(
                feed(
                    activity(
                        """
        <OriginAimedDepartureTime>2026-07-10T23:15:00Z</OriginAimedDepartureTime>
        """)))
            .getFirst();
    assertEquals("2026-07-11", position.serviceDate());
    assertEquals(15 * 60, position.originDepartureSecsLocal());
  }

  @Test
  void explicitServiceDayTakesPrecedenceAndBadTimestampStaysUnknown() throws Exception {
    String xml =
        feed(activity(
                """
        <DataFrameRef>2026-07-09</DataFrameRef>
        <OriginAimedDepartureTime>2026-07-10T08:15:00Z</OriginAimedDepartureTime>
        """))
            .replace("2026-07-10T08:20:00Z", "not-a-timestamp");
    var position = parse(xml).getFirst();
    assertEquals("2026-07-09", position.serviceDate());
    assertEquals(Instant.EPOCH, position.recordedAt());
  }

  @Test
  void skipsMissingOrInvalidCoordinatesWithoutDiscardingValidVehicles() throws Exception {
    String invalid = activity("").replace("52.76", "invalid");
    String missing =
        activity("")
            .replace(
                "<VehicleLocation><Latitude>52.76</Latitude><Longitude>-0.88</Longitude></VehicleLocation>",
                "");
    var positions = parse(feed(invalid + missing + activity("<VehicleRef>good</VehicleRef>")));
    assertEquals(1, positions.size());
    assertEquals("good", positions.getFirst().id());
  }

  @Test
  void validEmptyFeedIsDistinctFromInvalidResponse() throws Exception {
    assertTrue(parse(feed("")).isEmpty());
    for (String invalid : List.of("<html>error</html>", "<Siri/>", "<Siri>")) {
      var error = assertThrows(FeedException.class, () -> parse(invalid));
      assertEquals(FeedException.Kind.INVALID_RESPONSE, error.kind());
    }
  }

  @Test
  void rejectsExternalEntities() {
    String xml =
        "<!DOCTYPE Siri [<!ENTITY external SYSTEM 'file:///nonexistent'>]>" + feed("&external;");
    assertEquals(
        FeedException.Kind.INVALID_RESPONSE,
        assertThrows(FeedException.class, () -> parse(xml)).kind());
  }
}
