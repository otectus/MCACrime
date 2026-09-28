package dev.otectus.mcacrime.frisk;

/**
 * The six conditions of specification §11.2, and nothing else (M5.2).
 *
 * <p>Pure: facts in, refusal out. That is what makes "every transfer validates all six" checkable
 * rather than asserted — the live code gathers the facts and calls this, and the test calls it with
 * every combination.
 *
 * <p>The order matters and is the specification's own. Liveness and reach come before the restraint
 * condition, which comes before custody, which comes before the slot, which comes before capacity. Reporting the first thing that is wrong rather than the last means a
 * searcher whose subject just died is told that, not that the box is full.
 */
public final class FriskValidation {

    /**
     * Everything the six conditions need, gathered by the caller from live state.
     *
     * @param sessionLive        the session exists, has not expired and belongs to this searcher
     * @param ownsMenu           the searcher has this session's menu open
     * @param subjectAlive       the subject is loaded, alive and not removed
     * @param sameDimension      searcher and subject are in the same world
     * @param withinReach        the searcher is close enough
     * @param restraintSatisfied the subject still meets the required restraint condition
     * @param custodyMatches     the custody id and generation are the ones the session opened with
     * @param slotMatches        the source slot still holds what the searcher was shown
     * @param destinationTakesIt the searcher's inventory (or the escrow) will take the whole stack
     * @param delayElapsed       the server-owned interval since the last accepted transfer has passed
     * @param alreadyApplied     this transfer id has already been committed
     */
    public record Facts(boolean sessionLive, boolean ownsMenu, boolean subjectAlive,
                        boolean sameDimension, boolean withinReach, boolean restraintSatisfied,
                        boolean custodyMatches, boolean slotMatches,
                        boolean destinationTakesIt, boolean delayElapsed, boolean alreadyApplied) {
    }

    private FriskValidation() {
    }

    /** The first condition that fails, or {@link FriskRefusal#NONE}. */
    public static FriskRefusal check(Facts facts) {
        if (facts == null) {
            return FriskRefusal.NO_SESSION;
        }
        if (facts.alreadyApplied()) {
            return FriskRefusal.ALREADY_DONE;
        }
        if (!facts.sessionLive()) {
            return FriskRefusal.NO_SESSION;
        }
        if (!facts.ownsMenu()) {
            return FriskRefusal.NOT_YOUR_MENU;
        }
        if (!facts.subjectAlive() || !facts.sameDimension()) {
            return FriskRefusal.SUBJECT_UNAVAILABLE;
        }
        if (!facts.withinReach()) {
            return FriskRefusal.OUT_OF_REACH;
        }
        if (!facts.restraintSatisfied()) {
            return FriskRefusal.NOT_RESTRAINED;
        }
        if (!facts.custodyMatches()) {
            return FriskRefusal.CUSTODY_CHANGED;
        }
        if (!facts.slotMatches()) {
            return FriskRefusal.SLOT_CHANGED;
        }
        if (!facts.delayElapsed()) {
            return FriskRefusal.TOO_SOON;
        }
        if (!facts.destinationTakesIt()) {
            return FriskRefusal.DESTINATION_REFUSED;
        }
        return FriskRefusal.NONE;
    }

    /**
     * Whether the session may stay open at all.
     *
     * <p>The same facts minus the ones that are about a particular transfer. Re-asked every tick by
     * the menu's {@code stillValid}, which is precisely what upstream's container does not do: its
     * {@code stillValid} returns the literal {@code true}, so its screen survives death, a dimension
     * change, a logout and any distance at all.
     */
    public static boolean sessionStillValid(Facts facts) {
        return facts != null && facts.sessionLive() && facts.subjectAlive() && facts.sameDimension()
                && facts.withinReach() && facts.restraintSatisfied() && facts.custodyMatches();
    }
}
