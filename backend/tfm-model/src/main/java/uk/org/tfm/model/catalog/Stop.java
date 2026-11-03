package uk.org.tfm.model.catalog;

/** A stop returned by spatial queries or search; coordinates are WGS84. */
public record Stop(
    String atcoCode,
    String name,
    String indicator,
    String locality,
    String stopType,
    double lat,
    double lng,
    String crs) {}
