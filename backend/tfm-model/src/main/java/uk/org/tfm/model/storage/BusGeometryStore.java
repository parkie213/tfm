package uk.org.tfm.model.storage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.org.tfm.model.timetable.BusRoutePattern;
import uk.org.tfm.model.timetable.BusRouteShape;
import uk.org.tfm.model.timetable.SharedRoadSegment;

/** Persistence for derived bus map geometry, without routing or database-driver types. */
public interface BusGeometryStore {
  void rebuildPublishedShapes() throws StorageException;

  List<BusRoutePattern> shapelessRoutes() throws StorageException;

  List<BusRoutePattern> routesForBundling(
      double minLat, double minLng, double maxLat, double maxLng) throws StorageException;

  Set<String> retainSnappedRoutes(Set<String> currentIds) throws StorageException;

  void updateSnappedRouteNames(String id, String shortName, String longName)
      throws StorageException;

  boolean saveSnappedRoute(BusRouteShape shape) throws StorageException;

  int finishSnappedRoutes(long maxLengthMetres) throws StorageException;

  Map<String, String> routeOperators() throws StorageException;

  Map<String, String> routeColors() throws StorageException;

  void stageTrunks(List<SharedRoadSegment> segments, long maxLengthMetres) throws StorageException;

  void discardStagedTrunks() throws StorageException;
}
