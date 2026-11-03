package uk.org.tfm.model.catalog;

/** A catalogue stop and its straight-line geography distance from the query point. */
public record NearbyStop(Stop stop, double distanceM) {}
