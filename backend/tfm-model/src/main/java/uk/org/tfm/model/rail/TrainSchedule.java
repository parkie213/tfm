package uk.org.tfm.model.rail;

/**
 * One schedule (journey) from a Darwin Timetable snapshot: a dated train run and its origin/
 * destination. Identifiers are opaque and scoped to the source.
 *
 * @param rid RTTI unique id (per dated run) — natural key
 * @param uid CIF/schedule UID; joins a TRUST activation's {@code train_uid}
 * @param trainId signalling id / headcode, e.g. {@code 1F77}
 * @param ssd schedule start date, ISO {@code yyyy-MM-dd}
 * @param toc operating TOC code (e.g. {@code EM}), or empty
 * @param originTpl origin TIPLOC, or empty
 * @param destTpl destination TIPLOC, or empty
 * @param originDep public (else working) departure at origin, {@code HH:MM[:SS]}, or empty
 * @param destArr public (else working) arrival at destination, or empty
 */
public record TrainSchedule(
    String rid,
    String uid,
    String trainId,
    String ssd,
    String toc,
    String originTpl,
    String destTpl,
    String originDep,
    String destArr) {}
