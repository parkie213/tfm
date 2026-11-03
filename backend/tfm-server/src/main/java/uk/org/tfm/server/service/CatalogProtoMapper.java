package uk.org.tfm.server.service;

import uk.org.tfm.proto.v1.Locality;
import uk.org.tfm.proto.v1.Stop;

/** Maps catalog query results into the frontend contract at the application boundary. */
final class CatalogProtoMapper {
  private CatalogProtoMapper() {}

  static Stop toProto(uk.org.tfm.model.catalog.Stop stop) {
    return Stop.newBuilder()
        .setAtcoCode(stop.atcoCode())
        .setName(stop.name())
        .setIndicator(stop.indicator())
        .setLocality(stop.locality())
        .setStopType(stop.stopType())
        .setLat(stop.lat())
        .setLng(stop.lng())
        .setCrs(stop.crs())
        .build();
  }

  static Locality toProto(uk.org.tfm.model.catalog.Locality place) {
    return Locality.newBuilder()
        .setCode(place.code())
        .setName(place.name())
        .setQualifier(place.qualifier())
        .setLat(place.lat())
        .setLng(place.lng())
        .build();
  }
}
