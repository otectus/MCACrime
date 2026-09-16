package dev.otectus.mcacrime.mug.npc;

import java.util.Locale;

/**
 * Why a thief's mug ended without taking anything (spec §"Player mugging interaction").
 *
 * <p>The list is short on purpose. The requested counterplay is drawing a weapon, so incidental
 * damage, a step backwards, looking away, opening the inventory and tapping sprint are all absent:
 * none of them may end a mug, and a reason that does not exist cannot be added by accident.
 */
public enum NpcMugAbortReason {
    /** The victim drew a qualifying weapon. The one piece of counterplay the spec asks for. */
    VICTIM_ARMED,
    /** The villager's relationship with this player now meets the configured protection threshold. */
    RELATIONSHIP_PROTECTED,
    /**
     * The victim is inside a mugging protection window, in a pair cooldown with this thief, or has
     * already been mugged as often today as the daily cap allows (0.7.0).
     */
    VICTIM_PROTECTED,
    /** A guard reached the thief before the timer did (Phase 8 calls this). */
    GUARD_INTERVENTION,
    /** The thief died mid-threat. */
    THIEF_DEAD,
    /** The victim logged out, died, or changed dimension. */
    VICTIM_GONE,
    /** The victim got further away than a mugging can reach. */
    OUT_OF_RANGE,
    /**
     * The thief is law (0.7.2). MCA promoted them, an operator did, or {@code responderEntities}
     * changed under a reload — mid-threat, which is precisely when it must not be collapsed into
     * {@link #CANCELLED}: a mugging that stops because the mugger became a guard is the invariant
     * doing its job, and an operator reading "cancelled" cannot tell that from a reload.
     */
    ACTOR_BECAME_RESPONDER,
    /**
     * The thief has sand in their eyes and the victim is no longer within arm's reach (0.7.2 §13.5).
     *
     * <p>Its own reason rather than {@link #OUT_OF_RANGE}, which it superficially resembles: the
     * victim did not move. Collapsing the two would tell an operator reading the log that somebody
     * walked away when in fact somebody threw a bottle, and would hide the counterplay from the one
     * place it can be seen working.
     */
    THIEF_BLINDED,
    /** Ended administratively — a reload, a command, a server stop. */
    CANCELLED;

    /** {@code gui.mcacrime.outcome.npc_mug.<reason>}, shown on the victim's HUD as the bar fades. */
    public String outcomeKey() {
        return "gui.mcacrime.outcome.npc_mug." + name().toLowerCase(Locale.ROOT);
    }
}
