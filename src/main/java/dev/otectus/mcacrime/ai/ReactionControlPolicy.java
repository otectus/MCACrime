package dev.otectus.mcacrime.ai;

/**
 * Law responders keep their enforcement/combat AI; civilian fear must never steer them.
 *
 * <p>Pure, and deliberately kept that way: every rule here is a decision about who owns a villager,
 * and those are exactly the decisions that have to be readable without a server to reproduce them on.
 */
public final class ReactionControlPolicy {
    private ReactionControlPolicy() { }

    public static boolean mayControl(boolean responder, boolean lawHeld, VictimReactionState state) {
        return !lawHeld && (!responder || state == VictimReactionState.CAPTIVE);
    }

    /**
     * Whether a controller that took ownership at {@code heldGeneration} still owns the villager.
     *
     * <p>The "pre-empted, do not restore" rule, and the reason it needs a number rather than a flag.
     * A reaction controller ends by handing the villager back to their ordinary AI: stopping this
     * mod's navigation, clearing its target, dropping the speed modifier. That is correct when the
     * controller is the last thing that touched them, and actively harmful when it is not — an arrest
     * that pre-empted a panic two ticks earlier would have its walk order and its target wiped by the
     * panic's own tidy shutdown, and the guard would stand still holding nobody.
     *
     * <p>Generations make "am I still the owner?" answerable without asking what the other owner is:
     * a claim taken later has a strictly greater stamp, so a controller whose stamp is behind the live
     * one has been superseded and must leave the villager exactly as it found them.
     *
     * @param heldGeneration    what the controller took, or 0 if it never won a claim
     * @param currentGeneration what is live on the villager now, or 0 if nothing is
     */
    public static boolean mayRestore(long heldGeneration, long currentGeneration) {
        if (currentGeneration == 0L) {
            // Nothing owns the villager, so nothing can be trampled by handing them back.
            return true;
        }
        return heldGeneration != 0L && heldGeneration >= currentGeneration;
    }

    /**
     * {@link #mayControl} with pre-emption folded in: a controller that lost its claim stops driving
     * the villager at the next think, whether or not a {@code LawHold} happens to be stamped.
     */
    public static boolean mayControl(boolean responder, boolean lawHeld, VictimReactionState state,
                                     long heldGeneration, long currentGeneration) {
        return mayControl(responder, lawHeld, state) && mayRestore(heldGeneration, currentGeneration);
    }

    /** A guard refusing intimidation must not become robbable just because it has no fear controller. */
    public static boolean refusesMugging(boolean responder, boolean dynamic, VictimReactionState state) {
        return responder || dynamic && switch (state) {
            case RESISTING, DEFYING, PANICKING, FLEEING, SEEKING_HELP -> true;
            default -> false;
        };
    }
}
