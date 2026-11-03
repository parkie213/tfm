package uk.org.tfm.model.timetable;

/** A scheduled calling point with WGS84 coordinates; 0,0 means unknown. */
public record CallingPoint(
    int sequence,
    String atcoCode,
    String name,
    String locality,
    String scheduledTime,
    double lat,
    double lng) {}
