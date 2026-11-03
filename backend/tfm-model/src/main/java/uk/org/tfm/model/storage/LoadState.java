package uk.org.tfm.model.storage;

import java.time.Instant;

/** Persisted import/geometry revisions. Null fields mean that generation has not been loaded. */
public record LoadState(
    String feedVersion, Instant feedLoadedAt, String geometryKey, Instant geometryBuiltAt) {}
