package uk.org.tfm.model.catalog;

/**
 * A named place in the catalog. Coordinates are British National Grid (EPSG:27700) metres;
 * persistence converts them to WGS84 for spatial queries. Optional labels are empty strings.
 */
public record LocalityDefinition(
    String code,
    String name,
    String qualifier,
    String parentName,
    double easting,
    double northing) {}
