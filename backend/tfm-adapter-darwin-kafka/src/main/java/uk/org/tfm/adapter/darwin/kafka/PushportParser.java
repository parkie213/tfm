package uk.org.tfm.adapter.darwin.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.model.rail.MovementSnapshot;
import uk.org.tfm.model.rail.PlatformUpdate;
import uk.org.tfm.model.rail.TrainCallingPoint;
import uk.org.tfm.model.rail.TrainSchedule;
import uk.org.tfm.model.rail.TrainScheduleDetails;

/**
 * Parses a raw RDM Darwin Real Time (Push Port) Kafka message into a {@link PushportMessage}: the
 * platform updates (from {@code TS}) and the schedules (from {@code schedule}/SC) it carried, in
 * one pass. Supersedes the platform-only {@code PlatformParser}.
 *
 * <p>The RDM "…-JSON" topic delivers a JMS-over-Kafka envelope whose {@code bytes} field is the
 * Pport payload as a <em>JSON string</em> (the XML→JSON rendering of the Darwin Pport schema, v18).
 * Two quirks of that rendering matter: an element's text content becomes the empty-string key (so a
 * platform number is {@code plat[""]}); and a repeated element collapses to a single object when it
 * occurs once and to an array when it occurs more than once (so {@code uR}/{@code TS}/{@code
 * Location}/ {@code IP}/… are each "object or array"). Both {@code uR} (live) and {@code sR}
 * (snapshot) containers are handled.
 *
 * <p>Parsing is defensive: any malformed input drops the whole message and returns {@link
 * PushportMessage#EMPTY} — the feed is a national firehose and one bad message must not stall the
 * stream.
 *
 * <p>Scope: platform updates are emitted only for in-scope the supplied location-scope predicate
 * TIPLOCs carrying a {@code plat}; schedules are emitted only when the journey calls at an in-scope
 * TIPLOC (so {@code darwin_schedules} stays small and relevant to the platform board).
 */
public final class PushportParser {

  private static final Logger log = LoggerFactory.getLogger(PushportParser.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PushportParser() {}

  /**
   * Parses a raw Kafka record value into platform updates + schedules. Never null; never throws.
   */
  public static PushportMessage parse(byte[] raw, Predicate<String> scope) {
    if (raw == null || raw.length == 0) {
      return PushportMessage.EMPTY;
    }
    try {
      JsonNode pport = extractPport(raw);
      if (pport == null) {
        return PushportMessage.EMPTY;
      }
      List<PlatformUpdate> updates = new ArrayList<>();
      List<TrainScheduleDetails> schedules = new ArrayList<>();
      List<MovementSnapshot> movements = new ArrayList<>();
      Instant now = Instant.now(); // stamp every update in this message at the same instant
      // uR (real-time update) / sR (snapshot) both carry TS and schedule elements.
      for (JsonNode container : children(pport, "uR", "sR")) {
        for (JsonNode ts : asList(container.get("TS"))) {
          collectFromTs(ts, scope, now, updates, movements);
        }
        for (JsonNode sc : children(container, "schedule")) {
          collectFromSchedule(sc, scope, schedules);
        }
      }
      if (updates.isEmpty() && schedules.isEmpty() && movements.isEmpty()) {
        return PushportMessage.EMPTY;
      }
      return new PushportMessage(updates, schedules, movements);
    } catch (Exception e) {
      log.debug("Dropping Darwin Push Port message: {}", e.getMessage());
      return PushportMessage.EMPTY;
    }
  }

  /**
   * Unwraps the JMS-over-Kafka envelope to the inner Pport object. The payload is the {@code bytes}
   * field, itself a JSON string; if the value is already a bare Pport ({@code uR}/{@code sR} at the
   * top level) it is used as-is.
   */
  private static JsonNode extractPport(byte[] raw) throws Exception {
    JsonNode root = MAPPER.readTree(raw);
    if (root.has("uR") || root.has("sR")) {
      return root; // already a bare Pport
    }
    JsonNode bytes = root.get("bytes");
    if (bytes != null && bytes.isTextual()) {
      return MAPPER.readTree(bytes.asText());
    }
    return null;
  }

  // ---- TS (platform + movement actuals) ----

  private static void collectFromTs(
      JsonNode ts,
      Predicate<String> scope,
      Instant now,
      List<PlatformUpdate> out,
      List<MovementSnapshot> movementsOut) {
    if (ts == null) {
      return;
    }
    String rid = text(ts, "rid");
    if (rid.isEmpty()) {
      return;
    }
    for (JsonNode loc : asList(ts.get("Location"))) {
      String tpl = text(loc, "tpl");
      if (!scope.test(tpl)) {
        continue;
      }
      // Movement actuals/estimates: emitted for every timed location, independent of the platform
      // element below. The TS firehose carries arr/dep/pass far more often than a plat, and the
      // "at platform now" + position signals must not be gated on a plat being co-present.
      MovementSnapshot movement = movementOf(loc, rid, tpl, now);
      if (movement != null) {
        movementsOut.add(movement);
      }

      JsonNode plat = loc.get("plat");
      if (plat == null || !plat.isObject()) {
        continue; // no platform data at this calling point
      }
      String platform = text(plat, ""); // platform number is the element text → empty-string key
      String source = text(plat, "platsrc");
      out.add(
          new PlatformUpdate(
              rid,
              tpl,
              platform,
              bool(plat, "conf"),
              bool(plat, "platsup") || bool(plat, "cisPlatsup"),
              source.isEmpty() ? "P" : source,
              now,
              movement == null ? "" : movement.arrAt(),
              movement == null ? "" : movement.depAt()));
    }
  }

  /**
   * Builds a {@link MovementSnapshot} from a {@code Location}'s {@code arr}/{@code dep}/{@code
   * pass} children, or {@code null} if the location carried no time at all. Each child may have an
   * actual ({@code at}) and an estimate (public {@code et}, else working {@code wet}).
   */
  private static MovementSnapshot movementOf(JsonNode loc, String rid, String tpl, Instant now) {
    JsonNode arr = loc.get("arr");
    JsonNode dep = loc.get("dep");
    JsonNode pass = loc.get("pass");
    MovementSnapshot m =
        new MovementSnapshot(
            rid,
            tpl,
            text(arr, "at"),
            firstNonBlank(text(arr, "et"), text(arr, "wet")),
            text(dep, "at"),
            firstNonBlank(text(dep, "et"), text(dep, "wet")),
            text(pass, "at"),
            firstNonBlank(text(pass, "et"), text(pass, "wet")),
            now);
    return m.hasData() ? m : null;
  }

  // ---- schedule (SC) ----

  /**
   * Builds a {@link TrainScheduleDetails} from a {@code schedule} element, emitted only when the
   * journey calls at an in-scope TIPLOC. Origin = first location, destination = last; a public
   * calling point is a location with a non-blank {@code pta}/{@code ptd} (passing points {@code
   * PP}, working-time-only, are excluded). Locations come as separate JSON keys (not one ordered
   * list), so order is reconstructed as origin → intermediates → destination.
   */
  private static void collectFromSchedule(
      JsonNode sc, Predicate<String> scope, List<TrainScheduleDetails> out) {
    String rid = text(sc, "rid");
    if (rid.isEmpty()) {
      return;
    }
    List<JsonNode> ordered = new ArrayList<>();
    ordered.addAll(children(sc, "OR", "OPOR")); // origin
    ordered.addAll(children(sc, "IP", "OPIP")); // intermediate public/operational stops
    ordered.addAll(children(sc, "DT", "OPDT")); // destination

    List<TrainCallingPoint> cps = new ArrayList<>();
    int seq = 0;
    boolean inScope = false;
    String originTpl = "", originDep = "", destTpl = "", destArr = "";
    for (JsonNode loc : ordered) {
      String tpl = locationTpl(loc);
      if (tpl.isEmpty()) {
        continue;
      }
      if (scope.test(tpl)) {
        inScope = true;
      }
      String pta = text(loc, "pta");
      String ptd = text(loc, "ptd");
      if (originTpl.isEmpty()) {
        originTpl = tpl;
        originDep = firstNonBlank(ptd, text(loc, "wtd"));
      }
      destTpl = tpl;
      destArr = firstNonBlank(pta, text(loc, "wta"));
      if (!pta.isEmpty() || !ptd.isEmpty()) {
        cps.add(new TrainCallingPoint(rid, ++seq, tpl, pta, ptd));
      }
    }
    if (!inScope) {
      return; // not relevant to any board we serve
    }
    TrainSchedule header =
        new TrainSchedule(
            rid,
            text(sc, "uid"),
            text(sc, "trainId"),
            text(sc, "ssd"),
            text(sc, "toc"),
            originTpl,
            destTpl,
            originDep,
            destArr);
    out.add(new TrainScheduleDetails(header, cps));
  }

  /** TIPLOC of a location: {@code tpl} (current schema) or the space-padded {@code ftl} (older). */
  private static String locationTpl(JsonNode loc) {
    String tpl = text(loc, "tpl");
    return tpl.isEmpty() ? text(loc, "ftl") : tpl;
  }

  private static String firstNonBlank(String a, String b) {
    return !a.isEmpty() ? a : b;
  }

  // ---- JSON helpers (XML→JSON object-or-array rendering) ----

  /** A field that the XML→JSON rendering may emit as a single object or as an array; never null. */
  private static List<JsonNode> asList(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (node.isArray()) {
      List<JsonNode> out = new ArrayList<>(node.size());
      node.forEach(out::add);
      return out;
    }
    return List.of(node);
  }

  /** The object-or-array values of the named fields of {@code parent}, flattened. */
  private static List<JsonNode> children(JsonNode parent, String... fields) {
    List<JsonNode> out = new ArrayList<>();
    for (String field : fields) {
      out.addAll(asList(parent.get(field)));
    }
    return out;
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return "";
    }
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? "" : v.asText().trim();
  }

  /**
   * Tolerantly parses a Darwin boolean rendered as a string: {@code "true"} or {@code "1"} → true.
   */
  private static boolean bool(JsonNode node, String field) {
    String v = text(node, field);
    return "true".equalsIgnoreCase(v) || "1".equals(v);
  }
}
