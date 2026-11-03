package uk.org.tfm.server.service;

import uk.org.tfm.model.board.BoardEntry;
import uk.org.tfm.proto.v1.Coach;
import uk.org.tfm.proto.v1.Departure;

/** The frontend wire contract is an application boundary, not a feed adapter dependency. */
final class BoardProtoMapper {
  private BoardProtoMapper() {}

  static Departure toProto(BoardEntry entry) {
    String status =
        switch (entry.status()) {
          case ON_TIME -> "On time";
          case DELAYED -> "Delayed";
          case CANCELLED -> "Cancelled";
          case SCHEDULED -> "Scheduled";
          case UNKNOWN -> "";
        };
    String expected =
        entry
            .expectedTime()
            .orElseGet(
                () ->
                    switch (entry.status()) {
                      case ON_TIME, DELAYED, CANCELLED -> status;
                      case SCHEDULED, UNKNOWN -> "";
                    });
    Departure.Builder out =
        Departure.newBuilder()
            .setScheduledTime(entry.scheduledTime())
            .setExpectedTime(expected)
            .setDestination(entry.destination())
            .setOrigin(entry.origin())
            .setOperator(entry.operator())
            .setPlatformOrBay(entry.platform())
            .setStatus(status)
            .setDelayMinutes(entry.delayMinutes().orElse(0))
            .setIsLive(entry.live())
            .setServiceId(entry.serviceId())
            .setCoachCount(entry.coachCount());
    for (uk.org.tfm.model.board.Coach coach : entry.coaches()) {
      out.addCoaches(
          Coach.newBuilder()
              .setNumber(coach.number())
              .setCoachClass(coach.coachClass())
              .setLoading(coach.loading().orElse(0))
              .setLoadingKnown(coach.loading().isPresent())
              .setToilet(coach.toilet()));
    }
    return out.build();
  }
}
