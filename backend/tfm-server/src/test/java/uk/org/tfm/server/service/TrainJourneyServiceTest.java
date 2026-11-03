package uk.org.tfm.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import uk.org.tfm.model.rail.*;
import uk.org.tfm.model.storage.TrainScheduleStore;
import uk.org.tfm.server.darwin.pushport.PlatformScope;

class TrainJourneyServiceTest {
  private static TrainSchedule schedule(String rid, String destination) {
    return new TrainSchedule(
        rid, "uid", "1F77", "2026-10-28", "EM", "ORIGIN", destination, "10:00", "11:00");
  }

  private static class Schedules implements TrainScheduleStore {
    List<TrainSchedule> matches = List.of();
    List<LocalDate> dates = new ArrayList<>();
    boolean firstQueryEmpty;
    boolean arrival;

    public Optional<TrainSchedule> byRid(String rid) {
      return Optional.empty();
    }

    public Optional<String> headcodeForRid(String rid) {
      return Optional.empty();
    }

    public List<TrainCallingPoint> callingPoints(String rid) {
      return List.of(
          new TrainCallingPoint(rid, 1, "ORIGIN", "", "10:00"),
          new TrainCallingPoint(rid, 2, "UNKNOWN", "11:00", ""));
    }

    public List<TrainSchedule> findCallingAt(
        String tpl, String time, boolean arrival, LocalDate date) {
      dates.add(date);
      this.arrival = arrival;
      return firstQueryEmpty && dates.size() == 1 ? List.of() : matches;
    }

    public void upsertLiveBatch(List<TrainScheduleDetails> rows) {}

    public long evictOldSchedules(LocalDate cutoff) {
      return 0;
    }
  }

  private static PlatformScope scope() {
    return new PlatformScope() {
      public Optional<String> tplForAnyCrs(String crs) {
        return crs.equals("XXX") ? Optional.empty() : Optional.of("STATION");
      }

      public Optional<String> nameForTpl(String tpl) {
        return Optional.ofNullable(
            Map.of(
                    "ORIGIN",
                    "Origin",
                    "NOTTM",
                    "Nottingham Rail Station",
                    "PARK",
                    "Nottingham Parkway")
                .get(tpl));
      }

      public Optional<double[]> latLngForTpl(String tpl) {
        return tpl.equals("ORIGIN") ? Optional.of(new double[] {52.5, -1.2}) : Optional.empty();
      }
    };
  }

  @Test
  void exactDestinationWinsOverEarlierPartialMatchAndRetainsCallingPointDetails() {
    var schedules = new Schedules();
    schedules.matches = List.of(schedule("partial", "PARK"), schedule("exact", "NOTTM"));
    var response =
        new TrainJourneyService(schedules, scope()).getService("MMO", "10:00", "Nottingham", false);
    assertTrue(response.getFound());
    assertEquals("exact", response.getRid());
    assertEquals("1F77", response.getHeadcode());
    assertEquals("Origin", response.getOrigin());
    assertEquals(52.5, response.getStops(0).getLat());
    assertEquals(-1.2, response.getStops(0).getLng());
    assertEquals("UNKNOWN", response.getStops(1).getName());
    assertEquals(0, response.getStops(1).getLat());
    assertEquals("11:00", response.getStops(1).getArrival());
  }

  @Test
  void retriesPreviousServiceDateForArrivalAndPreservesArrivalQuery() {
    var schedules = new Schedules();
    schedules.firstQueryEmpty = true;
    schedules.matches = List.of(schedule("rid", "NOTTM"));
    var response = new TrainJourneyService(schedules, scope()).getService("MMO", "00:05", "", true);
    assertTrue(response.getFound());
    assertTrue(schedules.arrival);
    assertEquals(2, schedules.dates.size());
    assertEquals(schedules.dates.getFirst().minusDays(1), schedules.dates.get(1));
  }

  @Test
  void unknownStationDoesNotQuerySchedules() {
    var schedules = new Schedules();
    assertFalse(
        new TrainJourneyService(schedules, scope())
            .getService("XXX", "10:00", "", false)
            .getFound());
    assertTrue(schedules.dates.isEmpty());
  }

  @Test
  void absentSchedulesProduceExplicitNotFound() {
    var schedules = new Schedules();
    assertFalse(
        new TrainJourneyService(schedules, scope())
            .getService("MMO", "10:00", "", false)
            .getFound());
    assertEquals(2, schedules.dates.size());
  }
}
