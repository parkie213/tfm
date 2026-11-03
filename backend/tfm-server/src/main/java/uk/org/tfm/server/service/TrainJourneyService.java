package uk.org.tfm.server.service;

import java.time.LocalDate;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.rail.TrainSchedule;
import uk.org.tfm.model.storage.TrainScheduleStore;
import uk.org.tfm.proto.v1.GetTrainServiceResponse;
import uk.org.tfm.proto.v1.TrainCallingPoint;
import uk.org.tfm.server.darwin.pushport.PlatformScope;

/** Resolves train board rows to schedules and named, geolocated calling points. */
@Singleton
public final class TrainJourneyService {
  private final TrainScheduleStore scheduleDao;
  private final PlatformScope scope;

  @Inject
  public TrainJourneyService(TrainScheduleStore scheduleDao, PlatformScope scope) {
    this.scheduleDao = scheduleDao;
    this.scope = scope;
  }

  public GetTrainServiceResponse getService(
      String crs, String scheduledTime, String destHint, boolean isArrival) {
    // Resolve CRS → TIPLOC using the all-stations map so any GB station works (not just anchors).
    String tpl = scope.tplForAnyCrs(crs).orElse("");
    if (tpl.isBlank()) {
      return GetTrainServiceResponse.newBuilder().setFound(false).build();
    }

    // Try today first; fall back to yesterday for after-midnight services (e.g. 00:05 run from a
    // board still showing the previous day's schedule).
    LocalDate today = LocalDate.now();
    List<TrainSchedule> candidates =
        scheduleDao.findCallingAt(tpl, scheduledTime, isArrival, today);
    if (candidates.isEmpty()) {
      candidates = scheduleDao.findCallingAt(tpl, scheduledTime, isArrival, today.minusDays(1));
    }

    if (candidates.isEmpty()) {
      return GetTrainServiceResponse.newBuilder().setFound(false).build();
    }

    // Disambiguate by destination name: normalise both sides and look for a matching candidate.
    TrainSchedule chosen = disambiguate(candidates, destHint);

    // Build the response.
    String rid = chosen.rid();
    String originName = scope.nameForTpl(chosen.originTpl()).orElse(chosen.originTpl());
    String destName = scope.nameForTpl(chosen.destTpl()).orElse(chosen.destTpl());

    List<uk.org.tfm.model.rail.TrainCallingPoint> stops = scheduleDao.callingPoints(rid);
    GetTrainServiceResponse.Builder resp =
        GetTrainServiceResponse.newBuilder()
            .setFound(true)
            .setRid(rid)
            .setHeadcode(chosen.trainId())
            .setToc(chosen.toc())
            .setOrigin(originName)
            .setDestination(destName);

    for (uk.org.tfm.model.rail.TrainCallingPoint cp : stops) {
      String stopName = scope.nameForTpl(cp.tpl()).orElse(cp.tpl());
      double lat = 0.0;
      double lng = 0.0;
      double[] coords = scope.latLngForTpl(cp.tpl()).orElse(null);
      if (coords != null) {
        lat = coords[0];
        lng = coords[1];
      }
      resp.addStops(
          TrainCallingPoint.newBuilder()
              .setSequence(cp.seq())
              .setTpl(cp.tpl())
              .setName(stopName)
              .setLat(lat)
              .setLng(lng)
              .setArrival(cp.arr())
              .setDeparture(cp.dep())
              .build());
    }

    return resp.build();
  }

  /**
   * Picks the best candidate schedule from a list where more than one journey calls at the
   * requested TIPLOC at the requested time. The destination hint from the board row is used to
   * prefer the correct one.
   *
   * <p>Normalisation: lowercase, trim, strip trailing " rail station". Match priority:
   *
   * <ol>
   *   <li>Exact equality after normalisation.
   *   <li>Candidate dest starts with or contains the hint (handles abbreviations on boards).
   *   <li>First candidate (no match — take whatever we have).
   * </ol>
   */
  private TrainSchedule disambiguate(List<TrainSchedule> candidates, String destHint) {
    if (candidates.size() == 1 || destHint.isBlank()) {
      return candidates.get(0);
    }
    String normHint = normaliseDest(destHint);
    // Pass 1: exact match.
    for (TrainSchedule c : candidates) {
      String normDest = normaliseDest(scope.nameForTpl(c.destTpl()).orElse(c.destTpl()));
      if (normDest.equals(normHint)) {
        return c;
      }
    }
    // Pass 2: contains/starts-with (board may abbreviate destination).
    for (TrainSchedule c : candidates) {
      String normDest = normaliseDest(scope.nameForTpl(c.destTpl()).orElse(c.destTpl()));
      if (normDest.startsWith(normHint) || normDest.contains(normHint)) {
        return c;
      }
    }
    return candidates.get(0);
  }

  /** Normalises a destination name for fuzzy comparison: lowercase, trimmed, suffix stripped. */
  static String normaliseDest(String raw) {
    if (raw == null) {
      return "";
    }
    String s = raw.toLowerCase().trim();
    if (s.endsWith(" rail station")) {
      s = s.substring(0, s.length() - " rail station".length()).trim();
    }
    return s;
  }
}
