package uk.org.tfm.server.service;

import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.proto.v1.GetStationPlatformsResponse;
import uk.org.tfm.proto.v1.PlatformOccupant;
import uk.org.tfm.proto.v1.StationPlatform;
import uk.org.tfm.server.darwin.pushport.DarwinPlatformService;
import uk.org.tfm.server.darwin.pushport.PlatformScope;

/** Assembles the station platform API view from application platform state. */
@Singleton
public final class StationPlatformService {
  private final DarwinPlatformService platformService;
  private final PlatformFeedInfo platformFeedInfo;
  private final PlatformScope scope;

  @Inject
  public StationPlatformService(
      DarwinPlatformService platformService,
      PlatformFeedInfo platformFeedInfo,
      PlatformScope scope) {
    this.platformService = platformService;
    this.platformFeedInfo = platformFeedInfo;
    this.scope = scope;
  }

  public GetStationPlatformsResponse getPlatforms(String crs) {
    String stationName = scope.nameForCrs(crs).orElse(crs);
    String tpl = scope.tplForCrs(crs).orElse("");
    GetStationPlatformsResponse.Builder resp =
        GetStationPlatformsResponse.newBuilder()
            .setCrs(crs)
            .setStationName(stationName)
            .setGeneratedAt(Instant.now().toString())
            .setPlatformFeedEnabled(platformFeedInfo.isEnabled());

    if (!tpl.isBlank()) {
      for (DarwinPlatformService.PlatformLine line : platformService.stationBoard(tpl)) {
        StationPlatform.Builder platformBuilder =
            StationPlatform.newBuilder().setPlatform(line.platform());
        for (DarwinPlatformService.Occupant occupant : line.trains()) {
          platformBuilder.addTrains(
              PlatformOccupant.newBuilder()
                  .setHeadcode(occupant.headcode())
                  .setOrigin(occupant.origin())
                  .setDestination(occupant.destination())
                  .setPlatformConfirmed(occupant.confirmed())
                  .setAtPlatformNow(occupant.atPlatformNow())
                  .build());
        }
        resp.addPlatforms(platformBuilder.build());
      }
    }

    return resp.build();
  }
}
