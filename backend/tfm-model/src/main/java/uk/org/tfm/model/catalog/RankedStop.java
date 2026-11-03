package uk.org.tfm.model.catalog;

/** A stop and its application-comparable search ranking keys. */
public record RankedStop(Stop stop, int tier, int rankBucket, double score) {}
