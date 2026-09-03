package dev.otectus.mcacrime.enforcement;

import javax.annotation.Nullable;

/**
 * Where a player is in the arrest lifecycle. One stored value, and the only thing that decides whether
 * a challenge may open, who owns the arrest, whether the player is restrained, whether the escort
 * runs, and when the sentence starts.
 *
 * <p><b>Why seven values and not the ten the flow names.</b> {@code WANTED}, {@code RESISTING} and
 * {@code RELEASED} are deliberately absent, and their absence is the design rather than an omission.
 * Wanted is a projection of Heat against a threshold, recomputed on every Heat change; resisting
 * already lives in {@code PlayerCrimeData.resistingArrestUntilTick} on its own decaying clock; and
 * released is an edge, not a state — {@code PlayerReleasedFromJailEvent} already exists and already
 * fans out. Storing any of the three here would give one fact two writers, which is precisely the
 * defect this enum was introduced to remove: the guard-challenge spam came from five stores
 * disagreeing about whether an arrest was under way, not from a missing cooldown.
 *
 * <p>Read the projections through {@link ArrestPhases} rather than storing them.
 */
public enum ArrestPhase {

    /** Not being arrested. The only phase in which a guard may open a challenge. */
    NONE,

    /** A challenge is open and the player has not answered it. */
    CONFRONTED,

    /**
     * The player answered "surrender" and the arrest is being set up.
     *
     * <p>This phase is the fix for the repeating confrontation screen. The surrender click writes it
     * <em>before</em> the encounter closes, so the window between "answered" and "in custody" — during
     * which the arrest can still fail for want of an authority, a cell, or a sentence — is no longer a
     * hole the enforcement scan sees as {@code NONE} and re-challenges ten ticks later.
     */
    SURRENDERED,

    /** In lawful custody and restrained, with the escort not yet walking. */
    RESTRAINED,

    /** Being walked to the cell by the owning guard. */
    ESCORTING,

    /** Inside the cell with the sentence running. {@code JailState} is the authority from here. */
    JAILED,

    /**
     * The arrest could not be completed and is standing down.
     *
     * <p>A real terminal state with an expiry, not a cooldown bolted onto the trigger. An arrest that
     * fails has genuinely reached a state — "the law tried and could not" — and a guard declining to
     * re-open a screen for a few seconds afterwards is a property of that state rather than a timer
     * papering over one.
     */
    RECOVERY;

    /** Safe parse for a hand-edited or future save; anything unrecognised reads as {@link #NONE}. */
    public static ArrestPhase parse(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return NONE;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
