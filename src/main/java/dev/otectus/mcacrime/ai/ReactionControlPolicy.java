package dev.otectus.mcacrime.ai;

/** Law responders keep their enforcement/combat AI; civilian fear must never steer them. */
public final class ReactionControlPolicy {
    private ReactionControlPolicy() { }

    public static boolean mayControl(boolean responder, boolean lawHeld, VictimReactionState state) {
        return !lawHeld && (!responder || state == VictimReactionState.CAPTIVE);
    }

    /** A guard refusing intimidation must not become robbable just because it has no fear controller. */
    public static boolean refusesMugging(boolean responder, boolean dynamic, VictimReactionState state) {
        return responder || dynamic && switch (state) {
            case RESISTING, DEFYING, PANICKING, FLEEING, SEEKING_HELP -> true;
            default -> false;
        };
    }
}
