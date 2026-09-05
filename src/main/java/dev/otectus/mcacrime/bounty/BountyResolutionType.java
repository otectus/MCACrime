package dev.otectus.mcacrime.bounty;

import javax.annotation.Nullable;

import java.util.Locale;

/**
 * How a bounty was collected (0.5.1). Kept apart from the reward amount because a server can price
 * the three outcomes differently, and because "brought in alive" is the outcome the whole delivery
 * mechanism exists to make possible.
 */
public enum BountyResolutionType {

    /** The target died to the claimant. */
    KILLED,
    /** A responder arrested the target while the claimant held them. */
    ARRESTED,
    /** The claimant took the target into custody and delivered them. */
    CAPTURED_ALIVE;

    /** Parses a persisted name. Anything unrecognised reads as {@link #KILLED}, the original outcome. */
    public static BountyResolutionType parse(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return KILLED;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return KILLED;
        }
    }
}
