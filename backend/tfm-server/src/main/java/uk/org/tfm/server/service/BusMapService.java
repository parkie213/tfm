package uk.org.tfm.server.service;

import java.sql.SQLException;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.storage.LoadStateStore;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.proto.v1.GetMapDataStatusResponse;
import uk.org.tfm.proto.v1.GetRouteShapesInViewportResponse;
import uk.org.tfm.server.db.RouteShapeDao;

/** Bus route viewport responses and derived map-data freshness. */
@Singleton
public final class BusMapService {
  private final RouteShapeDao routeShapeDao;
  private final LoadStateStore loadStates;

  @Inject
  public BusMapService(RouteShapeDao routeShapeDao, LoadStateStore loadStates) {
    this.routeShapeDao = routeShapeDao;
    this.loadStates = loadStates;
  }

  public GetRouteShapesInViewportResponse routesInViewport(
      double minLat, double minLng, double maxLat, double maxLng) throws SQLException {
    // The DAO returns bundled geometry where available, otherwise published GTFS shapes.
    RouteShapeDao.TrunkViewportResult result =
        routeShapeDao.trunksInViewport(minLat, minLng, maxLat, maxLng);
    GetRouteShapesInViewportResponse.Builder resp =
        GetRouteShapesInViewportResponse.newBuilder()
            .setTruncated(result.truncated())
            .addAllTrunks(result.trunks());
    return resp.build();
  }

  public GetMapDataStatusResponse status() throws StorageException {
    Instant builtAt = loadStates.read().geometryBuiltAt();
    return GetMapDataStatusResponse.newBuilder()
        .setGeometryBuiltAtMs(builtAt == null ? 0L : builtAt.toEpochMilli())
        .build();
  }
}
