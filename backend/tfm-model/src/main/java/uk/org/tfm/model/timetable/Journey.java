package uk.org.tfm.model.timetable;

/** One scheduled run of a route, linked to its service calendar and optional supplied shape. */
public record Journey(
    String tripId, String routeId, String serviceId, String headsign, String shapeId) {}
