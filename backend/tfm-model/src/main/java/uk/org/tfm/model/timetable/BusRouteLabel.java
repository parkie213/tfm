package uk.org.tfm.model.timetable;

/** Display metadata for one route using a shared road segment. */
public record BusRouteLabel(String shortName, String longName, String operator, String color) {}
