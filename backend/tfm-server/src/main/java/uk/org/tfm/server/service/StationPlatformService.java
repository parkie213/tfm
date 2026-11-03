package uk.org.tfm.server.service;

import java.time.Instant;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.tfm.proto.v1.*;
import uk.org.tfm.server.darwin.pushport.DarwinPlatformService;
import uk.org.tfm.server.darwin.pushport.PlatformScope;

/**
 * Reconciles platform inventory with REST boards without guessing cross-source train identities.
 */
@Singleton
public final class StationPlatformService {
  private static final Logger log = LoggerFactory.getLogger(StationPlatformService.class);
  private final DarwinPlatformService platforms;
  private final PlatformFeedInfo feed;
  private final PlatformScope scope;
  private final StationBoardService boards;

  @Inject
  public StationPlatformService(
      DarwinPlatformService platforms,
      PlatformFeedInfo feed,
      PlatformScope scope,
      StationBoardService boards) {
    this.platforms = platforms;
    this.feed = feed;
    this.scope = scope;
    this.boards = boards;
  }

  public GetStationPlatformsResponse getPlatforms(String crs) {
    GetStationBoardResponse board = null;
    List<DarwinPlatformService.PlatformLine> lines = List.of();
    boolean platformAvailable = false;
    try {
      String tpl = scope.tplForCrs(crs).orElse("");
      if (!tpl.isBlank()) {
        lines = platforms.stationBoard(tpl);
        platformAvailable = true;
      }
    } catch (RuntimeException e) {
      log.warn("Platform enrichment unavailable for station {}.", crs);
    }
    try {
      board = boards.getBoard(crs, 15);
    } catch (RuntimeException e) {
      log.warn("REST platform board unavailable for station {}.", crs);
      if (!platformAvailable) throw e;
    }
    return reconcile(
        crs, scope.nameForCrs(crs).orElse(crs), board, lines, feed.isEnabled(), platformAvailable);
  }

  // No verified REST service-ID -> RID bridge exists. Never copy flags between these sources.
  static GetStationPlatformsResponse reconcile(
      String crs,
      String name,
      GetStationBoardResponse board,
      List<DarwinPlatformService.PlatformLine> lines,
      boolean enabled,
      boolean platformAvailable) {
    Map<String, StationPlatform.Builder> result = new LinkedHashMap<>();
    for (var line : lines)
      result.put(line.platform(), StationPlatform.newBuilder().setPlatform(line.platform()));
    if (board != null) {
      board.getRowsList().stream()
          .sorted(Comparator.comparingInt(StationBoardRow::getSortSecs))
          .filter(r -> !r.getPlatform().isBlank())
          .forEach(
              r -> {
                var p =
                    result.computeIfAbsent(
                        r.getPlatform().trim(),
                        label -> StationPlatform.newBuilder().setPlatform(label));
                if (p.getTrainsCount() < 2)
                  p.addTrains(
                      PlatformOccupant.newBuilder()
                          .setIdentity("rest:" + r.getServiceId())
                          .setOrigin(r.getOrigin())
                          .setDestination(r.getDestination())
                          .setBoardRow(r));
              });
    }
    for (var line : lines) {
      var p = result.get(line.platform());
      if (p.getTrainsCount() != 0) continue;
      line.trains().stream()
          .sorted(Comparator.comparing(DarwinPlatformService.Occupant::rid))
          .forEach(
              t ->
                  p.addTrains(
                      PlatformOccupant.newBuilder()
                          .setIdentity("darwin:" + t.rid())
                          .setHeadcode(t.headcode())
                          .setOrigin(t.origin())
                          .setDestination(t.destination())
                          .setPlatformConfirmed(t.confirmed())
                          .setAtPlatformNow(t.atPlatformNow() && !t.departed())
                          .setPresence(
                              t.departed()
                                  ? PlatformPresence.PLATFORM_PRESENCE_DEPARTED
                                  : t.atPlatformNow()
                                      ? PlatformPresence.PLATFORM_PRESENCE_PRESENT
                                      : PlatformPresence.PLATFORM_PRESENCE_UNKNOWN)));
    }
    var response =
        GetStationPlatformsResponse.newBuilder()
            .setCrs(crs)
            .setStationName(name)
            .setGeneratedAt(Instant.now().toString())
            .setPlatformFeedEnabled(enabled)
            .setBoardAvailable(board != null)
            .setPlatformDataAvailable(platformAvailable);
    result.values().forEach(response::addPlatforms);
    return response.build();
  }
}
