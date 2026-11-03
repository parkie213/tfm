package uk.org.tfm.model.vehicle;

import java.time.Instant;

/**
 * A reported vehicle position before application trip matching or road snapping. Vehicle and origin
 * identifiers are opaque and provider-scoped. Optional labels are empty strings. Bearing is 0–359,
 * or -1 when absent; an unknown observation time is Instant.EPOCH. The optional scheduled-journey
 * identity supports matching against a timetable: origin departure is seconds since local midnight
 * (-1 when absent), and serviceDate is a local ISO date (or empty).
 */
public record VehiclePosition(
    String id,
    String line,
    String destination,
    String operator,
    double lat,
    double lng,
    int bearing,
    Instant recordedAt,
    String originRef,
    int originDepartureSecsLocal,
    String serviceDate) {}
