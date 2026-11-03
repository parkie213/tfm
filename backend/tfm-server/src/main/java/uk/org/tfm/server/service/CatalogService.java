package uk.org.tfm.server.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import uk.org.tfm.model.catalog.RankedPlace;
import uk.org.tfm.model.catalog.RankedStop;
import uk.org.tfm.model.catalog.Stop;
import uk.org.tfm.model.catalog.StopPage;
import uk.org.tfm.model.storage.LocalityStore;
import uk.org.tfm.model.storage.StopStore;
import uk.org.tfm.model.storage.StorageException;
import uk.org.tfm.proto.v1.GetNearbyStopsResponse;
import uk.org.tfm.proto.v1.GetStopsInViewportResponse;
import uk.org.tfm.proto.v1.NearbyStop;
import uk.org.tfm.proto.v1.SearchItem;
import uk.org.tfm.proto.v1.SearchStopsResponse;

/** Catalog queries and the shared stop/place search ranking. */
@Singleton
public final class CatalogService {
  private static final int PLACE_SEARCH_LIMIT = 3;
  private final StopStore stopDao;
  private final LocalityStore localityDao;

  @Inject
  public CatalogService(StopStore stopDao, LocalityStore localityDao) {
    this.stopDao = stopDao;
    this.localityDao = localityDao;
  }

  public GetStopsInViewportResponse stopsInViewport(
      double minLat, double minLng, double maxLat, double maxLng) throws StorageException {
    StopPage result = stopDao.inViewport(minLat, minLng, maxLat, maxLng);
    GetStopsInViewportResponse.Builder resp =
        GetStopsInViewportResponse.newBuilder().setTruncated(result.truncated());
    for (Stop stop : result.stops()) {
      resp.addStops(CatalogProtoMapper.toProto(stop));
    }
    return resp.build();
  }

  public GetNearbyStopsResponse nearby(
      double lat, double lng, double radiusM, int limit, boolean busOnly) throws StorageException {
    if (!Double.isFinite(lat)
        || !Double.isFinite(lng)
        || lat < -90
        || lat > 90
        || lng < -180
        || lng > 180
        || !Double.isFinite(radiusM)
        || radiusM < 0
        || radiusM > 5000
        || limit < 0
        || limit > 500)
      throw new IllegalArgumentException(
          "Invalid coordinates, radius (0–5000 m), or limit (0–500).");
    var page =
        stopDao.nearby(lat, lng, radiusM == 0 ? 800 : radiusM, limit == 0 ? 100 : limit, busOnly);
    var response = GetNearbyStopsResponse.newBuilder().setTruncated(page.truncated());
    page.stops()
        .forEach(
            s ->
                response.addStops(
                    NearbyStop.newBuilder()
                        .setStop(CatalogProtoMapper.toProto(s.stop()))
                        .setDistanceM(s.distanceM())));
    return response.build();
  }

  public SearchStopsResponse search(String query, int limit) throws StorageException {
    if (query.length() < 2) return SearchStopsResponse.getDefaultInstance();
    List<RankedStop> stops = stopDao.search(query, limit);
    // Places (NPTG localities) interleave with stops by a unified rank rather than always sitting
    // on
    // top: a strongly-matched town outranks its own station, a weakly-matched one sinks below
    // real
    // stops. Fetch a few candidates; the merge + cap decide which actually show.
    List<RankedPlace> places = localityDao.search(query, PLACE_SEARCH_LIMIT);

    // Merge into one list ordered by tier (rail > metro > bus, strong place above all) then
    // rankBucket (stop_rank; 0 for places) then the proximity-adjusted name score — all
    // descending.
    record Ranked(int tier, int rankBucket, double score, SearchItem item) {}
    List<Ranked> merged = new ArrayList<>(stops.size() + places.size());
    for (RankedStop s : stops) {
      merged.add(
          new Ranked(
              s.tier(),
              s.rankBucket(),
              s.score(),
              SearchItem.newBuilder().setStop(CatalogProtoMapper.toProto(s.stop())).build()));
    }
    for (RankedPlace p : places) {
      merged.add(
          new Ranked(
              p.tier(),
              0,
              p.score(),
              SearchItem.newBuilder().setPlace(CatalogProtoMapper.toProto(p.place())).build()));
    }
    merged.sort(
        Comparator.comparingInt(Ranked::tier)
            .thenComparingInt(Ranked::rankBucket)
            .thenComparingDouble(Ranked::score)
            .reversed());

    int displayCap =
        (limit <= 0) ? StopStore.SEARCH_DEFAULT_LIMIT : Math.min(limit, StopStore.SEARCH_MAX_LIMIT);
    SearchStopsResponse.Builder resp = SearchStopsResponse.newBuilder();
    for (Ranked r : merged.subList(0, Math.min(displayCap, merged.size()))) {
      resp.addItems(r.item());
    }
    return resp.build();
  }
}
