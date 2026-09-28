package dev.otectus.mcacrime.tether;

import java.util.Locale;

/**
 * Why a subject is physically held to something (§3.3).
 *
 * <p>One engine for every hold. Before 0.7.5 a kidnapped player was teleported back by
 * {@code CustodyConfine}, a kidnapped villager wore a vanilla leash, and an escorted prisoner was
 * pulled by {@code EscortRestraint} — three mechanisms with three sets of edge cases, none of which
 * could express an anchor. These are the four things a tether can mean instead.
 */
public enum TetherKind {

    /** A guard walking a prisoner. Ends when the escort does. */
    ESCORT,
    /** A chain item between two entities. Somebody owns the chain and gets it back. */
    CHAIN,
    /** A fixed point: a fence knot, a hook, a weighted anchor. */
    ANCHOR,
    /**
     * A pre-0.7.5 kidnapping hold point, converted by the schema 15 reconciliation.
     *
     * <p>Its own kind rather than {@code ANCHOR} because no chain item was ever taken from anybody
     * to make it: migration fabricates no knot and drops no chain when it ends (§3.18).
     */
    LEGACY_HOLD;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Unknown names fall back to {@link #LEGACY_HOLD}: an unreadable hold is still a hold. */
    public static TetherKind parse(String raw) {
        if (raw == null) {
            return LEGACY_HOLD;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        for (TetherKind kind : values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return LEGACY_HOLD;
    }
}
