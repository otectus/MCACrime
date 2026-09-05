package dev.otectus.mcacrime.ai.thief;

/**
 * Where one thief is in the loop of spec §"Thief behavioral state machine" (0.5.1).
 *
 * <p>Deliberately not a mob AI goal. A thief is a normal MCA villager whose ordinary behaviour is
 * suspended for a few seconds by an in-memory controller; these are the phases of that suspension,
 * and every one of them ends by handing the villager back to MCA.
 */
public enum ThiefState {
    /** Newly tracked, or just handed back. One think away from scouting. */
    IDLE,
    /** Looking for somebody worth robbing, on a jittered scan interval. */
    SCOUTING,
    /** Walking to a chosen victim. Guard risk can still call this off. */
    APPROACHING,
    /** In reach and facing them: the threat itself, and the first moment a guard may intervene. */
    THREATENING,
    /** The timed session is running. Property has not moved yet. */
    MUGGING,
    /** Running, either from a guard or from a mug that ended. */
    FLEEING,
    /** Not looking for anybody for a while. */
    COOLDOWN,
    /** Taken into custody. Nothing here drives the villager until custody ends (Phase 8). */
    ARRESTED,
    /** Dead; the controller is dropped on the next tick. */
    DEAD
}
