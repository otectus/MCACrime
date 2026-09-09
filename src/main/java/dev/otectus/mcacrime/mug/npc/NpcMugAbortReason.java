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
    /** A guard reached the thief before the timer did (Phase 8 calls this). */
    GUARD_INTERVENTION,
    /** The thief died mid-threat. */
    THIEF_DEAD,
    /** The victim logged out, died, or changed dimension. */
    VICTIM_GONE,
    /** The victim got further away than a mugging can reach. */
    OUT_OF_RANGE,
    /** Ended administratively — a reload, a command, a server stop. */
    CANCELLED;

    /** {@code gui.mcacrime.outcome.npc_mug.<reason>}, shown on the victim's HUD as the bar fades. */
    public String outcomeKey() {
        return "gui.mcacrime.outcome.npc_mug." + name().toLowerCase(Locale.ROOT);
    }
}
