package uk.org.tfm.adapter.darwin.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.rail.MovementSnapshot;
import uk.org.tfm.model.rail.PlatformUpdate;
import uk.org.tfm.model.rail.TrainCallingPoint;
import uk.org.tfm.model.rail.TrainScheduleDetails;

/**
 * Unit tests for {@link PushportParser}, built from the real on-the-wire RDM Darwin "…-JSON" shape
 * captured from the live feed: a JMS-over-Kafka envelope whose {@code bytes} field is the Pport
 * payload as a JSON string, with the platform number under the empty-string key and object-or-array
 * collapsing. Covers platform updates (with movement actuals) and live SC schedules. Uses a
 * supplied scope predicate so NTNG (Nottingham) is in scope; "LSTREET" is an invented out-of-scope
 * TIPLOC.
 */
class PushportParserTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Predicate<String> scope = "NTNG"::equals;

  /** Wraps an inner Pport JSON string in the JMS-over-Kafka envelope (the real record value). */
  private static byte[] envelope(String innerPportJson) {
    ObjectNode outer = MAPPER.createObjectNode();
    outer.put("messageType", "bytes");
    outer.put("bytes", innerPportJson);
    try {
      return MAPPER.writeValueAsBytes(outer);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static List<PlatformUpdate> platforms(String inner) {
    return PushportParser.parse(envelope(inner), scope).platformUpdates();
  }

  private static List<TrainScheduleDetails> schedules(String inner) {
    return PushportParser.parse(envelope(inner), scope).schedules();
  }

  // ---- platform updates ----

  @Test
  void parsesInScopeLocationAndFiltersOutOfScope() {
    // Location as an ARRAY: NTNG (in scope) + LSTREET (out of scope).
    String inner =
        """
        {"ts":"2026-06-12T16:00:00+01:00","version":"18.0","uR":{"TS":{
          "rid":"202606121234567","uid":"L12345","ssd":"2026-06-12","Location":[
            {"tpl":"NTNG","wtd":"16:30","plat":{"platsrc":"A","conf":"true","":"3"}},
            {"tpl":"LSTREET","wtd":"16:45","plat":{"platsrc":"P","":"2"}}
          ]}}}""";
    List<PlatformUpdate> updates = platforms(inner);

    assertEquals(1, updates.size(), "only the in-scope NTNG location should be returned");
    PlatformUpdate u = updates.get(0);
    assertEquals("202606121234567", u.rid());
    assertEquals("NTNG", u.tpl());
    assertEquals("3", u.platform(), "platform number is the empty-string key");
    assertTrue(u.confirmed(), "conf=\"true\" → confirmed");
    assertFalse(u.suppressed());
    assertEquals("A", u.source());
  }

  @Test
  void parsesSingleLocationObject() {
    String inner =
        """
        {"version":"18.0","uR":{"TS":{"rid":"202606126733004","Location":
          {"tpl":"NTNG","plat":{"platsrc":"M","":"5"}}}}}""";
    List<PlatformUpdate> updates = platforms(inner);

    assertEquals(1, updates.size());
    assertEquals("5", updates.get(0).platform());
    assertEquals("M", updates.get(0).source());
    assertFalse(updates.get(0).confirmed(), "no conf key → not confirmed");
  }

  @Test
  void parsesBarePportWithoutEnvelope() {
    String inner =
        """
        {"uR":{"TS":{"rid":"202606120000001","Location":
          {"tpl":"NTNG","plat":{"platsrc":"A","":"6"}}}}}""";
    List<PlatformUpdate> updates =
        PushportParser.parse(inner.getBytes(StandardCharsets.UTF_8), scope).platformUpdates();

    assertEquals(1, updates.size());
    assertEquals("6", updates.get(0).platform());
  }

  @Test
  void suppressedPlatformIsFlagged() {
    String inner =
        """
        {"uR":{"TS":{"rid":"202606129999999","Location":
          {"tpl":"NTNG","plat":{"platsup":"true","":"4"}}}}}""";
    List<PlatformUpdate> updates = platforms(inner);

    assertEquals(1, updates.size());
    assertTrue(updates.get(0).suppressed(), "platsup=\"true\" → suppressed");
  }

  @Test
  void locationWithNoPlatIsSkipped() {
    String inner =
        """
        {"uR":{"TS":{"rid":"202606121111111","Location":
          {"tpl":"NTNG","wta":"10:30"}}}}""";
    assertTrue(platforms(inner).isEmpty(), "a Location with no plat object must be skipped");
  }

  // ---- movement actuals (at-platform-now) ----

  @Test
  void parsesArrivalActualAsAtPlatformNow() {
    // Arrived (arr.at) but not yet departed (no dep.at) → at the platform.
    String inner =
        """
        {"uR":{"TS":{"rid":"r1","Location":
          {"tpl":"NTNG","plat":{"":"3"},"arr":{"at":"16:58","src":"TD"}}}}}""";
    PlatformUpdate u = platforms(inner).get(0);
    assertEquals("16:58", u.arrAt());
    assertEquals("", u.depAt());
    assertTrue(u.atPlatformNow(), "arrived, not departed → at platform");
  }

  @Test
  void departedTrainIsNotAtPlatform() {
    String inner =
        """
        {"uR":{"TS":{"rid":"r1","Location":
          {"tpl":"NTNG","plat":{"":"3"},"arr":{"at":"16:58"},"dep":{"at":"17:01"}}}}}""";
    PlatformUpdate u = platforms(inner).get(0);
    assertEquals("17:01", u.depAt());
    assertFalse(u.atPlatformNow(), "departed → not at platform");
  }

  // ---- movement snapshots (the separate firehose, emitted with or without a plat) ----

  private static List<MovementSnapshot> movements(String inner) {
    return PushportParser.parse(envelope(inner), scope).movements();
  }

  @Test
  void emitsMovementForLocationWithNoPlat() {
    // A movement-only Location (arr/dep, no plat) yields NO PlatformUpdate but DOES yield a
    // movement.
    String inner =
        """
        {"uR":{"TS":{"rid":"r1","Location":
          {"tpl":"NTNG","arr":{"at":"16:58","src":"TD"},"dep":{"et":"17:01","src":"Darwin"}}}}}""";
    assertTrue(platforms(inner).isEmpty(), "no plat → no platform update");
    List<MovementSnapshot> ms = movements(inner);
    assertEquals(1, ms.size(), "a timed Location must yield a movement even with no plat");
    MovementSnapshot m = ms.get(0);
    assertEquals("NTNG", m.tpl());
    assertEquals("16:58", m.arrAt());
    assertEquals("17:01", m.depEt(), "dep et captured");
    assertEquals("", m.depAt());
    assertTrue(m.atPlatformNow(), "arrived (arr.at) but not departed (no dep.at) → at platform");
  }

  @Test
  void capturesEstimatesPublicPreferredOverWorking() {
    // arr carries both et (public) and wet (working) → et wins; pass carries only wet → wet used.
    String inner =
        """
        {"uR":{"TS":{"rid":"r1","Location":
          {"tpl":"NTNG","arr":{"et":"17:05","wet":"17:04"},"pass":{"wet":"17:06"}}}}}""";
    MovementSnapshot m = movements(inner).get(0);
    assertEquals("17:05", m.arrEt(), "public et preferred over working wet");
    assertEquals("17:06", m.passEt(), "working wet used when no public et");
    assertEquals("17:05", m.forwardEt(), "forward ETA = arrival estimate");
  }

  @Test
  void locationWithNoTimesYieldsNoMovement() {
    String inner =
        """
        {"uR":{"TS":{"rid":"r1","Location":{"tpl":"NTNG","plat":{"":"3"}}}}}""";
    assertTrue(
        movements(inner).isEmpty(), "a Location with a plat but no times yields no movement");
    assertEquals(1, platforms(inner).size(), "but it still yields a platform update");
  }

  @Test
  void movementFilteredToInScopeTiploc() {
    String inner =
        """
        {"uR":{"TS":{"rid":"r1","Location":{"tpl":"LSTREET","arr":{"at":"16:58"}}}}}""";
    assertTrue(movements(inner).isEmpty(), "out-of-scope TIPLOC movement is dropped");
  }

  // ---- schedules (SC) ----

  @Test
  void parsesInScopeScheduleWithCallingPoints() {
    // Calls at NTNG (in scope) → emitted. OR=origin (dep only), IP=intermediate, DT=dest (arr
    // only).
    String inner =
        """
        {"uR":{"schedule":{"rid":"202606140000001","uid":"C00001","trainId":"1F77",
          "ssd":"2026-06-14","toc":"EM",
          "OR":{"tpl":"STPX","ptd":"20:32"},
          "IP":[{"tpl":"NTNG","pta":"22:10","ptd":"22:12"},{"tpl":"LESTER","pta":"21:30","ptd":"21:31"}],
          "PP":[{"tpl":"SOMEJN","wtp":"21:00"}],
          "DT":{"tpl":"SHEFF","pta":"22:41"}}}}""";
    List<TrainScheduleDetails> recs = schedules(inner);

    assertEquals(1, recs.size(), "a schedule calling at an in-scope TIPLOC must be emitted");
    TrainScheduleDetails r = recs.get(0);
    assertEquals("1F77", r.header().trainId(), "headcode = trainId");
    assertEquals("STPX", r.header().originTpl());
    assertEquals("20:32", r.header().originDep());
    assertEquals("SHEFF", r.header().destTpl());
    assertEquals("22:41", r.header().destArr());
    // Public calling points only (OR, both IPs, DT); the PP passing point is excluded.
    assertEquals(4, r.callingPoints().size());
    TrainCallingPoint ntng =
        r.callingPoints().stream().filter(cp -> cp.tpl().equals("NTNG")).findFirst().orElseThrow();
    assertEquals("22:10", ntng.arr());
    assertEquals("22:12", ntng.dep());
  }

  @Test
  void filtersOutOfScopeSchedule() {
    String inner =
        """
        {"uR":{"schedule":{"rid":"r2","trainId":"9Z99","ssd":"2026-06-14",
          "OR":{"tpl":"AAAA","ptd":"09:00"},
          "IP":[{"tpl":"BBBB","pta":"09:10","ptd":"09:11"}],
          "DT":{"tpl":"CCCC","pta":"09:20"}}}}""";
    assertTrue(schedules(inner).isEmpty(), "a schedule with no in-scope calling point is dropped");
  }

  // ---- defensive ----

  @Test
  void malformedInputReturnsEmpty() {
    byte[] garbage = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05};
    assertTrue(
        PushportParser.parse(garbage, scope).platformUpdates().isEmpty(),
        "malformed input must yield empty, not an exception");
    assertTrue(
        PushportParser.parse("{\"bytes\":\"not json\"}".getBytes(StandardCharsets.UTF_8), scope)
            .platformUpdates()
            .isEmpty(),
        "a bytes field that isn't JSON must be dropped");
  }

  @Test
  void nullAndEmptyInputReturnEmpty() {
    assertTrue(PushportParser.parse(null, scope).platformUpdates().isEmpty());
    assertTrue(PushportParser.parse(new byte[0], scope).schedules().isEmpty());
  }
}
