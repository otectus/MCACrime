package dev.otectus.mcacrime.relationship;

import java.util.Locale;
import java.util.Optional;

/**
 * How closely one villager is related to another, as far as this mod needs to care.
 *
 * <p>MCA models a family tree; this is the flattened view of it that a rule can be written against.
 * The tiers are ordered from closest outwards, and that order is the priority used when somebody
 * qualifies twice — a spouse who is also a distant cousin is a spouse, because that is the
 * relationship a player would name if asked.
 *
 * <p>{@link #parse(String)} never throws and is case-insensitive, because every value that reaches it
 * came out of a config list an operator typed by hand. An unknown name is a reported problem, not a
 * crashed server.
 */
public enum FamilyTier {
    SPOUSE,
    PARENT,
    CHILD,
    SIBLING,
    EXTENDED,
    IN_LAW;

    /** The tier named by {@code name}, ignoring case and surrounding space, or empty if unknown. */
    public static Optional<FamilyTier> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String trimmed = name.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        for (FamilyTier tier : values()) {
            if (tier.name().equals(trimmed)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
