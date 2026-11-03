package uk.org.tfm.model.catalog;

/** A named place returned by search; coordinates are WGS84. */
public record Locality(String code, String name, String qualifier, double lat, double lng) {}
