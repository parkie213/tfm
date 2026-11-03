package uk.org.tfm.model.rail;

/**
 * One public calling point of a Darwin Timetable journey: a location element whose public arrival
 * ({@code pta}) or public departure ({@code ptd}) is non-blank. Passing points (working times only)
 * are excluded by the parser. Identifiers are opaque and scoped to the source.
 *
 * @param rid Darwin RTTI unique id for the dated journey — foreign key to {@code darwin_schedules}
 * @param seq 1-based sequence position within the journey (public stops only, not counting passing
 *     points)
 * @param tpl TIPLOC of the calling point
 * @param arr public arrival time ({@code pta}), {@code HH:MM[:SS]}, or empty at the origin
 * @param dep public departure time ({@code ptd}), {@code HH:MM[:SS]}, or empty at the destination
 */
public record TrainCallingPoint(String rid, int seq, String tpl, String arr, String dep) {}
