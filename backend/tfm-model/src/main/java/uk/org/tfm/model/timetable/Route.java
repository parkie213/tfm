package uk.org.tfm.model.timetable;

/** A named route supplied by the timetable source; color is optional. */
public record Route(String id, String agencyId, String shortName, String longName, String color) {}
