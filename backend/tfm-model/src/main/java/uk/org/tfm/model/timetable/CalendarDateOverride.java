package uk.org.tfm.model.timetable;

/**
 * Overrides the regular service calendar for one ISO local date (a GTFS calendar_dates row). The
 * GTFS exception type is 1 to add service or 2 to remove it.
 */
public record CalendarDateOverride(String serviceId, String exceptionDate, int exceptionType) {}
