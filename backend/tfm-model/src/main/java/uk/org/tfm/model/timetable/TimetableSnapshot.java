package uk.org.tfm.model.timetable;

/** Prepared source revision: callers can skip decoding when this version is already in storage. */
public interface TimetableSnapshot {
  String version();

  /** The revision's tables, read lazily; nothing is decoded until a table is opened. */
  TimetableSource read();
}
