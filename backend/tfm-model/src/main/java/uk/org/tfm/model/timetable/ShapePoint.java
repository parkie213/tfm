package uk.org.tfm.model.timetable;

/** An ordered point of supplied route geometry, in WGS84 coordinates. */
public record ShapePoint(String shapeId, int seq, double lat, double lng) {}
