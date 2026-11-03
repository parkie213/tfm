package uk.org.tfm.adapter.bods;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import uk.org.tfm.model.feed.FeedException;
import uk.org.tfm.model.vehicle.VehiclePosition;

/** Decodes SIRI-VM XML into observations and normalized scheduled-journey identifiers. */
final class SiriVmParser {
  private static final String SIRI_NS = "http://www.siri.org.uk/siri";
  private static final ZoneId LONDON = ZoneId.of("Europe/London");

  private SiriVmParser() {}

  static List<VehiclePosition> parse(byte[] body) throws FeedException {
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(true);
      // Harden the parser (no external entities).
      dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder db = dbf.newDocumentBuilder();
      Element root = db.parse(new ByteArrayInputStream(body)).getDocumentElement();

      // Validate this is a complete SIRI response (guards truncated/non-SIRI bodies).
      if (root == null
          || !"Siri".equals(root.getLocalName())
          || elements(root, "ServiceDelivery").isEmpty()) {
        throw new FeedException(
            "Response is not a valid SIRI document",
            FeedException.Kind.INVALID_RESPONSE,
            Optional.empty());
      }

      List<VehiclePosition> out = new ArrayList<>();
      for (Element activity : elements(root, "VehicleActivity")) {
        VehiclePosition v = toVehicle(activity);
        if (v != null) {
          out.add(v);
        }
      }
      return out;
    } catch (FeedException e) {
      throw e;
    } catch (Exception e) {
      throw new FeedException(
          "Failed to parse SIRI-VM XML: " + e.getClass().getSimpleName(),
          FeedException.Kind.INVALID_RESPONSE,
          e);
    }
  }

  private static VehiclePosition toVehicle(Element activity) {
    Element mvj = firstChild(activity, "MonitoredVehicleJourney");
    if (mvj == null) {
      return null;
    }
    Element loc = firstChild(mvj, "VehicleLocation");
    Double lat = parseDouble(text(loc, "Latitude"));
    Double lng = parseDouble(text(loc, "Longitude"));
    if (lat == null || lng == null) {
      return null;
    }
    String line = firstNonBlank(text(mvj, "PublishedLineName"), text(mvj, "LineRef"));
    String operator = text(mvj, "OperatorRef");
    String destination = cleanName(text(mvj, "DestinationName"));
    String id = firstNonBlank(text(mvj, "VehicleRef"), text(activity, "ItemIdentifier"));
    int bearing = parseBearing(text(mvj, "Bearing"));
    Instant recordedAt = parseInstant(text(activity, "RecordedAtTime"));

    // Journey identity for GTFS trip matching (best-effort; any field may be absent).
    String originRef = text(mvj, "OriginRef");
    OffsetDateTime originDep = parseOffsetDateTime(text(mvj, "OriginAimedDepartureTime"));
    int originDepartureSecsLocal =
        originDep == null ? -1 : originDep.atZoneSameInstant(LONDON).toLocalTime().toSecondOfDay();
    // Prefer the feed's explicit service day (DataFrameRef); else derive it from the origin
    // departure's local date so calendar matching still has a date to key on.
    String dataFrameRef = text(mvj, "DataFrameRef");
    String serviceDate =
        !dataFrameRef.isBlank()
            ? dataFrameRef
            : (originDep != null
                ? originDep.atZoneSameInstant(LONDON).toLocalDate().toString()
                : "");

    return new VehiclePosition(
        id,
        line,
        destination,
        operator,
        lat,
        lng,
        bearing,
        recordedAt,
        originRef,
        originDepartureSecsLocal,
        serviceDate);
  }

  // ---- DOM helpers (namespace-aware, scoped to a parent) ----

  private static List<Element> elements(Element parent, String localName) {
    NodeList nl = parent.getElementsByTagNameNS(SIRI_NS, localName);
    List<Element> out = new ArrayList<>(nl.getLength());
    for (int i = 0; i < nl.getLength(); i++) {
      out.add((Element) nl.item(i));
    }
    return out;
  }

  /** First direct-or-descendant element with the given local name, or null. */
  private static Element firstChild(Element parent, String localName) {
    NodeList nl = parent.getElementsByTagNameNS(SIRI_NS, localName);
    return nl.getLength() == 0 ? null : (Element) nl.item(0);
  }

  private static String text(Element parent, String localName) {
    if (parent == null) {
      return "";
    }
    NodeList nl = parent.getElementsByTagNameNS(SIRI_NS, localName);
    if (nl.getLength() == 0) {
      return "";
    }
    Node n = nl.item(0);
    String t = n.getTextContent();
    return t == null ? "" : t.trim();
  }

  private static String cleanName(String s) {
    return s.replace('_', ' ').replaceAll("\\s+", " ").trim();
  }

  private static String firstNonBlank(String a, String b) {
    return !a.isBlank() ? a : b;
  }

  private static Double parseDouble(String s) {
    try {
      return s.isBlank() ? null : Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int parseBearing(String s) {
    try {
      if (s.isBlank()) {
        return -1;
      }
      int b = (int) Math.round(Double.parseDouble(s));
      return ((b % 360) + 360) % 360;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static Instant parseInstant(String s) {
    try {
      return OffsetDateTime.parse(s).toInstant();
    } catch (Exception e) {
      return Instant.EPOCH; // treated as stale by the freshness filter
    }
  }

  /**
   * Parses an ISO offset datetime, or null if blank/unparseable (field is optional in the feed).
   */
  private static OffsetDateTime parseOffsetDateTime(String s) {
    try {
      return s.isBlank() ? null : OffsetDateTime.parse(s);
    } catch (Exception e) {
      return null;
    }
  }
}
