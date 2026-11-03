package uk.org.tfm.adapter.darwin;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import uk.org.tfm.model.board.Board;
import uk.org.tfm.model.board.BoardEntry;
import uk.org.tfm.model.board.Coach;

/** Converts Darwin's JSON schema into transport model values. */
final class DarwinBoardParser {
  private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

  private DarwinBoardParser() {}

  static Board parse(JsonNode board, boolean arrivals) {
    List<BoardEntry> entries = new ArrayList<>();
    JsonNode services = board.get("trainServices");
    if (services != null && services.isArray()) {
      for (JsonNode s : services) {
        String scheduled = text(s, arrivals ? "sta" : "std");
        String expected = text(s, arrivals ? "eta" : "etd");
        String location = firstLocationName(s.get(arrivals ? "origin" : "destination"));
        entries.add(
            new BoardEntry(
                text(s, "serviceID"),
                scheduled,
                parse(expected) == null ? Optional.empty() : Optional.of(expected),
                arrivals ? "" : location,
                arrivals ? location : "",
                text(s, "operator"),
                text(s, "platform"),
                status(scheduled, expected),
                true,
                delayMinutes(scheduled, expected),
                intVal(s, "length"),
                coaches(s.get("formation"))));
      }
    }
    return new Board(
        text(board, "locationName"),
        text(board, "generatedAt"),
        nrccMessages(board.get("nrccMessages")),
        entries);
  }

  private static List<Coach> coaches(JsonNode formation) {
    if (formation == null) {
      return List.of();
    }
    JsonNode arr = formation.get("coaches");
    if (arr == null || !arr.isArray()) {
      return List.of();
    }
    List<Coach> out = new ArrayList<>();
    for (JsonNode c : arr) {
      JsonNode loadingNode = c.get("loading");
      boolean known =
          c.path("loadingSpecified").asBoolean(false)
              || (loadingNode != null && !loadingNode.isNull());
      out.add(
          new Coach(
              text(c, "number"),
              text(c, "coachClass"),
              known
                  ? OptionalInt.of(
                      loadingNode == null || loadingNode.isNull() ? 0 : loadingNode.asInt(0))
                  : OptionalInt.empty(),
              toiletText(c.get("toilet"))));
    }
    return out;
  }

  private static String toiletText(JsonNode toilet) {
    if (toilet == null) {
      return "";
    }
    // Darwin's toilet node is { status, Value } where Value is the type (e.g. "None", "Standard").
    String value = text(toilet, "Value");
    return value.isBlank() ? text(toilet, "status") : value;
  }

  private static String firstLocationName(JsonNode locations) {
    if (locations == null || !locations.isArray() || locations.isEmpty()) {
      return "";
    }
    JsonNode loc = locations.get(0);
    String name = text(loc, "locationName");
    String via = text(loc, "via");
    return via.isBlank() ? name : name + " " + via; // Darwin's "via" already reads "via X"
  }

  private static BoardEntry.Status status(String sched, String exp) {
    if (exp.isBlank()) {
      return BoardEntry.Status.UNKNOWN;
    }
    if (exp.equalsIgnoreCase("On time")) {
      return BoardEntry.Status.ON_TIME;
    }
    if (exp.equalsIgnoreCase("Cancelled")) {
      return BoardEntry.Status.CANCELLED;
    }
    if (exp.equalsIgnoreCase("Delayed")) {
      return BoardEntry.Status.DELAYED;
    }
    return exp.equals(sched) ? BoardEntry.Status.ON_TIME : BoardEntry.Status.DELAYED;
  }

  private static OptionalInt delayMinutes(String sched, String exp) {
    LocalTime s = parse(sched);
    LocalTime e = parse(exp);
    if (s == null || e == null) {
      return OptionalInt.empty();
    }
    long minutes = Duration.between(s, e).toMinutes();
    if (minutes < -720) { // crossed midnight forwards
      minutes += 1440;
    }
    return OptionalInt.of((int) Math.max(0, minutes));
  }

  private static LocalTime parse(String hhmm) {
    try {
      return LocalTime.parse(hhmm, HHMM);
    } catch (Exception e) {
      return null; // "On time", "Cancelled", "Delayed", empty, etc.
    }
  }

  private static String nrccMessages(JsonNode messages) {
    if (messages == null || !messages.isArray() || messages.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (JsonNode m : messages) {
      String raw = m.isTextual() ? m.asText() : firstNonBlank(text(m, "value"), text(m, "Value"));
      String clean = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
      if (!clean.isBlank()) {
        if (sb.length() > 0) {
          sb.append("\n");
        }
        sb.append(clean);
      }
    }
    return sb.toString();
  }

  private static String firstNonBlank(String a, String b) {
    return !a.isBlank() ? a : b;
  }

  private static int intVal(JsonNode node, String field) {
    if (node == null) {
      return 0;
    }
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? 0 : v.asInt(0);
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return "";
    }
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? "" : v.asText();
  }
}
