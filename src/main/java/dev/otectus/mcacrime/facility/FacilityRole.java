package dev.otectus.mcacrime.facility;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What MCA: Crime uses a building for, in MCA: Crime's own vocabulary.
 *
 * <p>Deliberately independent of Townstead's building type strings. Townstead names buildings for what
 * they <em>are</em> — {@code kitchen_l1}, {@code dock_l2} — and a settlement has no notion of a jail at
 * all: the reviewed resource tree has no jail or guardhouse definition, so nothing here can be inferred
 * from a type name. A kitchen is not a cell because it has a door, and a pen is not a cell because it
 * holds animals. Every role is an explicit assignment an operator made, which is also what makes it
 * safe to hold somebody in one.
 *
 * <p>The ids are stable strings: they appear in {@code /crime facility} and in saved data, so renaming
 * one would orphan existing assignments.
 */
public enum FacilityRole {

    /** Patrol anchor, report destination and shift meeting point. Holds nobody. */
    GUARD_POST("guard_post", "Guard post", 0),

    /** A safe custody region with capacity and a release point. The only role an arrest may target. */
    JAIL_CELL("jail_cell", "Jail cell", 1),

    /**
     * A building containing assigned cells and on-duty guard facilities.
     *
     * <p>Capacity zero on purpose: a guardhouse aggregates the cells assigned inside it and does not
     * itself become one. Treating every room of a building as a cell is how a prisoner ends up locked
     * in somebody's pantry.
     */
    GUARDHOUSE("guardhouse", "Guardhouse", 0),

    /** Holds deposited evidence and impounded goods; excluded from ordinary settlement sourcing. */
    EVIDENCE_STORAGE("evidence_storage", "Evidence storage", 0),

    /** A validated place a prisoner may recover in. Care, not confinement. */
    CARE_ROOM("care_room", "Care room", 1),

    /** Where public cases, bounties and approved civic work are displayed. */
    PUBLIC_NOTICE("public_notice", "Public notice location", 0);

    private final String id;
    private final String label;
    private final int defaultCapacity;

    FacilityRole(String id, String label, int defaultCapacity) {
        this.id = id;
        this.label = label;
        this.defaultCapacity = defaultCapacity;
    }

    public String id() {
        return id;
    }

    /** The name an operator reads. */
    public String label() {
        return label;
    }

    /** How many prisoners one assignment of this role holds before another is needed. */
    public int defaultCapacity() {
        return defaultCapacity;
    }

    /** Whether an arrest may be routed to this role. */
    public boolean holdsPrisoners() {
        return this == JAIL_CELL;
    }

    /** Whether a prisoner who has become unfit may be recovered here. */
    public boolean providesCare() {
        return this == CARE_ROOM;
    }

    /**
     * Resolves an id or a constant name, in any case. Empty for anything else.
     *
     * <p>Optional rather than a throw or a default: this is read from operator input and from saved
     * data, and both have to be able to say "that is not a role" without taking a command or a world
     * load down with them.
     */
    public static Optional<FacilityRole> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(role -> role.id.equals(needle) || role.name().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }
}
