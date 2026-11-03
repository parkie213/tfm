package uk.org.tfm.model.catalog;

/** A place and its application-comparable search ranking keys. */
public record RankedPlace(Locality place, int tier, double score) {}
