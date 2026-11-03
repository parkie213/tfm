package uk.org.tfm.adapter.darwin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.board.Board;
import uk.org.tfm.model.board.BoardEntry.Status;

class DarwinBoardParserTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static Board board(String services, boolean arrivals) throws Exception {
    return DarwinBoardParser.parse(
        JSON.readTree(
            """
        {"locationName":"Melton Mowbray","generatedAt":"2026-10-28T12:00:00Z",
         "nrccMessages":[{"value":"<p>Platform <b>change</b></p>"},"Check boards"],
         "trainServices":%s}
        """
                .formatted(services)),
        arrivals);
  }

  @Test
  void departureNormalizesTimesLocationsAndMessages() throws Exception {
    Board board =
        board(
            """
        [{"serviceID":"train-1","std":"23:58","etd":"00:08","operator":"EMR","platform":"2",
          "destination":[{"locationName":"Nottingham","via":"via Leicester"}],"length":3}]
        """,
            false);
    var entry = board.entries().getFirst();
    assertEquals("Melton Mowbray", board.stationName());
    assertEquals("2026-10-28T12:00:00Z", board.generatedAt());
    assertEquals("Platform change\nCheck boards", board.messages());
    assertEquals("train-1", entry.serviceId());
    assertEquals("23:58", entry.scheduledTime());
    assertEquals("00:08", entry.expectedTime().orElseThrow());
    assertEquals(10, entry.delayMinutes().orElseThrow());
    assertEquals(Status.DELAYED, entry.status());
    assertEquals("Nottingham via Leicester", entry.destination());
    assertEquals("", entry.origin());
    assertEquals("EMR", entry.operator());
    assertEquals("2", entry.platform());
    assertEquals(3, entry.coachCount());
    assertTrue(entry.live());
  }

  @Test
  void arrivalsUseArrivalTimeAndOrigin() throws Exception {
    var entry =
        board(
                """
        [{"sta":"12:10","eta":"On time","std":"12:12","etd":"12:30",
          "origin":[{"locationName":"Derby"}],"destination":[{"locationName":"Stansted"}]}]
        """,
                true)
            .entries()
            .getFirst();
    assertEquals("12:10", entry.scheduledTime());
    assertEquals("Derby", entry.origin());
    assertEquals("", entry.destination());
    assertEquals(Status.ON_TIME, entry.status());
    assertTrue(entry.expectedTime().isEmpty());
  }

  @Test
  void cancelledAndDelayedAreStatusesRatherThanTimes() throws Exception {
    var entries =
        board(
                """
        [{"std":"12:00","etd":"Cancelled"},{"std":"12:30","etd":"Delayed"}]
        """,
                false)
            .entries();
    assertEquals(Status.CANCELLED, entries.get(0).status());
    assertEquals(Status.DELAYED, entries.get(1).status());
    for (var entry : entries) {
      assertTrue(entry.expectedTime().isEmpty());
      assertTrue(entry.delayMinutes().isEmpty());
    }
  }

  @Test
  void missingPredictionIsUnknown() throws Exception {
    var entry = board("[{\"std\":\"12:00\"}]", false).entries().getFirst();
    assertEquals(Status.UNKNOWN, entry.status());
    assertTrue(entry.expectedTime().isEmpty());
    assertTrue(entry.delayMinutes().isEmpty());
    assertTrue(entry.coaches().isEmpty());
  }

  @Test
  void unknownCoachLoadingRemainsDistinctFromReportedZero() throws Exception {
    var coaches =
        board(
                """
        [{"formation":{"coaches":[
          {"number":"A","coachClass":"First","toilet":{"Value":"Standard"}},
          {"number":"B","loading":0},
          {"number":"C","loading":75,"loadingSpecified":true}
        ]}}]
        """,
                false)
            .entries()
            .getFirst()
            .coaches();
    assertTrue(coaches.get(0).loading().isEmpty());
    assertEquals("First", coaches.get(0).coachClass());
    assertEquals("Standard", coaches.get(0).toilet());
    assertEquals(0, coaches.get(1).loading().orElseThrow());
    assertEquals(75, coaches.get(2).loading().orElseThrow());
  }

  @Test
  void emptyOrMissingServicesProduceEmptyBoard() throws Exception {
    assertTrue(board("null", false).entries().isEmpty());
    assertTrue(board("[]", true).entries().isEmpty());
    assertTrue(DarwinBoardParser.parse(JSON.readTree("{}"), false).entries().isEmpty());
  }
}
