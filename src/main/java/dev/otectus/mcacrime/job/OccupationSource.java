package dev.otectus.mcacrime.job;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Which route asked for an occupation change (0.7.2 §10.1).
 *
 * <p>Persisted with the record because the routes are not interchangeable. A settlement sweep must
 * find an unemployed adult and a reachable station; a grandfathered migration must not; an operator
 * command may override the probability rules but not the guard rules. Keeping the origin means a later
 * reconciliation can tell a legitimately stationless wild thief from one whose station reservation
 * simply failed.
 */
public enum OccupationSource {

    /** No recorded origin — a record written before schema 12. */
    UNKNOWN,
    /** The ambient {@code CriminalJobAssignmentSweep}. Requires an unemployed adult. */
    SETTLEMENT_SWEEP,
    /** A villager reached a placed Mask Station through Crime's own discovery service. */
    STATION_RECRUITMENT,
    /** MCA's retained vanilla job-site assignment behaviour rebinding an existing Thief. */
    NATIVE_REBIND,
    /** {@code /crime job} or another operator entry point. */
    OPERATOR,
    /** A third party through {@code McaCrimeApi}. */
    API,
    /** The configured stationless exception (spec §10.5). */
    WILD,
    /** Schema migration of a record that predates the exclusive profession. */
    MIGRATION;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static OccupationSource parse(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /**
     * Whether this route has to prove the villager is an unemployed adult with a reachable station.
     *
     * <p>False for the three routes that are repairing or grandfathering something that already
     * exists, and for the explicitly configured wild exception.
     */
    public boolean requiresStation() {
        return this == SETTLEMENT_SWEEP || this == STATION_RECRUITMENT;
    }
}
