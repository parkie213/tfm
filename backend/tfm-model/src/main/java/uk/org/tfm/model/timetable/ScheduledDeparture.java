package uk.org.tfm.model.timetable;

/** A scheduled bus departure, without an inferred prediction. */
public record ScheduledDeparture(
    String scheduledTime, String line, String destination, String operator, String tripId) {}
