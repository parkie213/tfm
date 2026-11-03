package uk.org.tfm.model.timetable;

/** A regular weekday operating pattern bounded by ISO local dates. */
public record ServiceCalendar(
    String serviceId,
    boolean monday,
    boolean tuesday,
    boolean wednesday,
    boolean thursday,
    boolean friday,
    boolean saturday,
    boolean sunday,
    String startDate,
    String endDate) {}
