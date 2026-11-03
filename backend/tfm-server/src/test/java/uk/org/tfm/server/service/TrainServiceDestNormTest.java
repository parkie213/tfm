package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the destination-normalisation helper used by the {@code GetTrainService}
 * disambiguation logic.
 */
class TrainServiceDestNormTest {

  @Test
  void stripsRailStationSuffixCaseInsensitive() {
    assertEquals("nottingham", TrainJourneyService.normaliseDest("Nottingham Rail Station"));
  }

  @Test
  void trimsBothEnds() {
    assertEquals("london st pancras", TrainJourneyService.normaliseDest("  London St Pancras  "));
  }

  @Test
  void lowercases() {
    assertEquals("sheffield", TrainJourneyService.normaliseDest("Sheffield"));
  }

  @Test
  void nullReturnsEmpty() {
    assertEquals("", TrainJourneyService.normaliseDest(null));
  }

  @Test
  void blankReturnsBlank() {
    assertEquals("", TrainJourneyService.normaliseDest("  "));
  }

  @Test
  void noSuffixPassesThrough() {
    assertEquals("leeds", TrainJourneyService.normaliseDest("Leeds"));
  }
}
