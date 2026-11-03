package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.board.BoardEntry;
import uk.org.tfm.model.board.Coach;

class BoardProtoMapperTest {
  @Test
  void mapsReportedTimesAndCoachLoadingToWireContract() {
    var row =
        BoardProtoMapper.toProto(
            new BoardEntry(
                "service-1",
                "12:00",
                Optional.of("12:05"),
                "Nottingham",
                "",
                "EMR",
                "2",
                BoardEntry.Status.DELAYED,
                true,
                OptionalInt.of(5),
                2,
                List.of(
                    new Coach("A", "First", OptionalInt.empty(), "Standard"),
                    new Coach("B", "Standard", OptionalInt.of(0), ""))));
    assertEquals("12:05", row.getExpectedTime());
    assertEquals(5, row.getDelayMinutes());
    assertEquals("Delayed", row.getStatus());
    assertTrue(row.getIsLive());
    assertEquals("service-1", row.getServiceId());
    assertEquals("Nottingham", row.getDestination());
    assertEquals("2", row.getPlatformOrBay());
    assertFalse(row.getCoaches(0).getLoadingKnown());
    assertTrue(row.getCoaches(1).getLoadingKnown());
    assertEquals(0, row.getCoaches(1).getLoading());
  }

  @Test
  void statusWithoutAnExpectedTimePreservesExistingFrontendLabels() {
    for (var status :
        List.of(
            BoardEntry.Status.ON_TIME,
            BoardEntry.Status.CANCELLED,
            BoardEntry.Status.DELAYED,
            BoardEntry.Status.UNKNOWN)) {
      var row =
          BoardProtoMapper.toProto(
              new BoardEntry(
                  "",
                  "12:00",
                  Optional.empty(),
                  "",
                  "",
                  "",
                  "",
                  status,
                  true,
                  OptionalInt.empty(),
                  0,
                  List.of()));
      assertEquals(row.getStatus(), row.getExpectedTime());
      assertEquals(0, row.getDelayMinutes());
    }
  }
}
