package dev.otectus.mcacrime.decree;

import java.util.Locale;

/**
 * Every reason a decree is refused or cannot be acted on (0.7.8). Each has its own message, so a ruler
 * is told exactly which rule stopped them.
 */
public enum DecreeRefusal {
    /** Decrees, or the whole Capitals integration, are switched off. */
    DISABLED,
    /** Capitals cannot be asked right now, so neither authority nor rank can be verified. */
    NOT_VERIFIABLE,
    /** The village is not a capital. */
    NOT_A_CAPITAL,
    /** The capital has no reigning court (founded or pending, not active). */
    CAPITAL_INACTIVE,
    /** The actor does not sit on this capital's throne. */
    NOT_SOVEREIGN,
    /** The actor is not standing in the village. */
    NOT_IN_VILLAGE,
    /** A ruler cannot decree against themselves. */
    TARGET_SELF,
    /** The target holds Noble status or higher in this capital. */
    TARGET_NOBLE,
    /** The target is not an online player the server can identify. */
    TARGET_UNKNOWN,
    /** An open decree against this target in this village already exists. */
    DUPLICATE,
    /** A decree against this target here ended too recently. */
    COOLDOWN,
    /** The capital already holds as many open decrees as it may. */
    CAPITAL_FULL,
    /** The world's decree table is full. */
    STORE_FULL,
    /** The world's crime data is read-only. */
    READ_ONLY,
    /** No open decree matches. */
    NOT_FOUND,
    /** The screen was out of date, replayed, or not this player's. */
    STALE,
    /** The decree has no fine to pay. */
    NOT_PAYABLE,
    /** The fine could not be taken. */
    CANNOT_PAY;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code mcacrime.decree.refused.<id>}. */
    public String messageKey() {
        return "mcacrime.decree.refused." + id();
    }
}
