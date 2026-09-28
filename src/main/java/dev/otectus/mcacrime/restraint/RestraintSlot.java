package dev.otectus.mcacrime.restraint;

import java.util.Locale;
import java.util.Optional;

/**
 * The three independent body regions a restraint can occupy (0.7.5 §6.2).
 *
 * <p>Independent is the whole point, and the reason the single {@code captivity/RestraintType} enum
 * it replaces could not express this: a subject may be hooded, cuffed and shackled at once, each
 * piece has its own durability and its own applier, and removing one must leave the other two
 * exactly as they were.
 *
 * <p>There is deliberately no {@code NONE}: "nothing on that slot" is an absent
 * {@link AppliedRestraint}, not a value. A sentinel enum constant is what made the old type
 * ambiguous about whether a record meant "free" or "unknown".
 */
public enum RestraintSlot {

    HEAD,
    ARMS,
    LEGS;

    /** The lower-case form used in NBT and on the wire. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a saved or received slot name, empty when it names no slot.
     *
     * <p>{@link Optional} rather than a defaulted value, because every caller is either reading a
     * save (where an unknown slot must be quarantined, not silently reassigned to the arms) or
     * reading a packet (where an unknown slot must be refused).
     */
    public static Optional<RestraintSlot> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        for (RestraintSlot slot : values()) {
            if (slot.name().equals(name)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }
}
