package dev.otectus.mcacrime.job;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Where a villager stands in the Thief occupation, as 0.7.2 §10.4 defines the states.
 *
 * <p>Before this release a thief was a boolean: the record said {@code THIEF} and everything else was
 * inferred. That could not express the three situations the spec distinguishes — a novice who has not
 * yet earned the job, an established thief whose station was broken, and an occupation suspended
 * because MCA's profession capability is missing — so all three behaved like a working thief. They are
 * separate constants here, and {@link #mayMug()} is the single place that decides which of them is
 * allowed to act.
 */
public enum OccupationStatus {

    /** No occupation at all. The state of every villager in the world bar a handful. */
    NONE,
    /**
     * Requested and validated as far as an unloaded or not-yet-arrived villager allows, but not
     * applied. A pending occupation is not a Thief: spec §9.4 forbids reporting one as assigned.
     */
    PENDING,
    /** Committed, holding a station claim, and not yet established. Loses the job if the claim goes. */
    ACTIVE_BOUND_NOVICE,
    /** Committed, holding a station claim, past the establishment milestone. */
    ACTIVE_BOUND_ESTABLISHED,
    /** Established but with no station claim — a broken station, or a grandfathered wild thief. */
    ESTABLISHED_UNBOUND,
    /** Committed once, but the capability or the world state needed to keep it honest is missing. */
    SUSPENDED,
    /** Held the occupation and no longer does. Kept so cooldowns and history survive the retirement. */
    RETIRED;

    /** The lowercase form used in NBT and command output. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@link #id()}. Anything unrecognised, blank or null reads as {@link #NONE}. */
    public static OccupationStatus parse(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    /** True while the villager is visibly employed as a Thief, bound to a station or not. */
    public boolean employed() {
        return this == ACTIVE_BOUND_NOVICE || this == ACTIVE_BOUND_ESTABLISHED
                || this == ESTABLISHED_UNBOUND;
    }

    /** True for the two states that hold a station claim. */
    public boolean bound() {
        return this == ACTIVE_BOUND_NOVICE || this == ACTIVE_BOUND_ESTABLISHED;
    }

    /** True once the establishment milestone has been recorded. */
    public boolean established() {
        return this == ACTIVE_BOUND_ESTABLISHED || this == ESTABLISHED_UNBOUND;
    }

    /**
     * Whether this state may run a mugging (spec §10.4).
     *
     * <p>Exactly the employed states. A pending request, a suspended occupation and a retired one all
     * read false — "a novice whose required claim fails must not use a grace period to operate as an
     * unauthorized stationless Thief" is this method and not a comment somewhere else.
     */
    public boolean mayMug() {
        return employed();
    }
}
