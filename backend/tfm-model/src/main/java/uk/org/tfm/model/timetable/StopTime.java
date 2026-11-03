package uk.org.tfm.model.timetable;

/**
 * A calling point with departure seconds since service-day midnight, including values beyond 86400.
 */
public record StopTime(String tripId, String stopId, int stopSequence, int departureSecs) {}
