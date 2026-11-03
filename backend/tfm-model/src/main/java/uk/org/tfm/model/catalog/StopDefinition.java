package uk.org.tfm.model.catalog;

/**
 * A stop catalog entry, with WGS84 coordinates and optional labels represented by empty strings.
 */
public record StopDefinition(
    String atcoCode,
    String commonName,
    String indicator,
    String localityName,
    String parentLocalityName,
    double lat,
    double lng,
    String stopType,
    String crs) {}
