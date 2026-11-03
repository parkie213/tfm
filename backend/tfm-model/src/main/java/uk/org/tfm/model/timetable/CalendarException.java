package uk.org.tfm.model.timetable;

/** A calendar change on an ISO local date: exception type 1 adds service; type 2 removes it. */
public record CalendarException(String serviceId, String exceptionDate, int exceptionType) {}
