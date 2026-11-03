package uk.org.tfm.model.timetable;

import java.time.LocalDate;

/** A reported bus journey identity used for calendar-valid timetable matching. */
public record JourneyKey(
    String line, String originRef, int originDepSecs, LocalDate serviceDate, String destination) {}
