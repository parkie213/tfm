package uk.org.tfm.model.board;

import java.util.List;

/**
 * A station board. Times use the station's local time; generatedAt is the source's ISO timestamp.
 */
public record Board(
    String stationName, String generatedAt, String messages, List<BoardEntry> entries) {
  public Board {
    entries = List.copyOf(entries);
  }
}
