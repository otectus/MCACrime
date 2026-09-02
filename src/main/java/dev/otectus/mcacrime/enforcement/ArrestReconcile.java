package dev.otectus.mcacrime.enforcement;

/**
 * What to do with a stored arrest when the player logs in, or when a restart interrupted one.
 *
 * <p>Pure and dependency-free, because the ordering of these rules <em>is</em> the recovery design and
 * it is the only part worth asserting without a world. The guiding rule throughout: an arrest may end
 * in a sentence or end in nothing, but it may never leave a player restrained with no way out. Every
 * branch that cannot reach a cell reaches {@link Outcome#RECOVER}, which releases custody and hands
 * the player back to themselves.
 */
public final class ArrestReconcile {

    public enum Outcome {
        /** Nothing in flight, or the stored phase is stale. Drop the arrest record and carry on. */
        CLEAR,
        /**
         * Finish the arrest now, without replaying the walk.
         *
         * <p>Deliberately not a resumed escort. Walking a guard across a village that nobody was
         * present to watch is theatre; the sentence is the mechanic, and completing it immediately is
         * both the smaller amount of code and the more honest outcome.
         */
        COMPLETE_NOW,
        /** The same session, the same guard, still in time: pick the walk back up where it stopped. */
        RESUME_ESCORT,
        /** The arrest cannot be completed. Release custody, tell the player, stand the guards down. */
        RECOVER
    }

    private ArrestReconcile() {
    }

    /**
     * @param stored            the phase read from the player's NBT
     * @param jailed            whether a sentence is already running
     * @param lawfulCustody     whether the world custody table still holds them lawfully
     * @param anchorResolves    whether the stored destination still exists in a live dimension
     * @param guardStillPresent whether the owning guard is loaded and alive
     * @param expired           whether the escort deadline has passed on the player's own clock
     */
    public static Outcome decide(ArrestPhase stored, boolean jailed, boolean lawfulCustody,
                                 boolean anchorResolves, boolean guardStillPresent, boolean expired) {
        if (stored == null || stored == ArrestPhase.NONE) {
            return Outcome.CLEAR;
        }
        // A running sentence outranks everything stored here. JailService owns a jailed player, and a
        // phase that disagrees with it is the stale one.
        if (jailed) {
            return Outcome.CLEAR;
        }
        if (stored == ArrestPhase.JAILED) {
            // Marked jailed but serving nothing: a crash between release and save. Let them go.
            return Outcome.CLEAR;
        }
        if (stored == ArrestPhase.RECOVERY) {
            return expired ? Outcome.CLEAR : Outcome.RECOVER;
        }
        if (stored == ArrestPhase.CONFRONTED) {
            // The screen did not survive the disconnect and the player never answered it. Nothing was
            // taken from them, so nothing has to be given back.
            return Outcome.CLEAR;
        }
        if (!anchorResolves) {
            // The jail was destroyed, unassigned, or lived in a dimension that no longer exists. There
            // is nowhere to put them, and holding a restrained player for a cell that does not exist is
            // the one outcome this whole subsystem must never produce.
            return Outcome.RECOVER;
        }
        if (stored == ArrestPhase.ESCORTING && guardStillPresent && !expired) {
            return Outcome.RESUME_ESCORT;
        }
        if (lawfulCustody || stored == ArrestPhase.SURRENDERED || stored == ArrestPhase.RESTRAINED
                || stored == ArrestPhase.ESCORTING) {
            return Outcome.COMPLETE_NOW;
        }
        return Outcome.CLEAR;
    }
}
