package dev.otectus.mcacrime.activity;

/**
 * One thing another system may try to do to a villager MCA: Crime is currently acting on.
 *
 * <p>These are deliberately named after the <em>behaviour</em> rather than after the mod that
 * performs it. Vanilla, MCA and Townstead all send a villager to work, to bed and off on a social
 * wander, and MCA: Crime's answer to "may that start right now?" is the same in every case. Keeping
 * the vocabulary neutral is also what lets {@link OperationPolicy} stay a pure table with no compat
 * layer behind it: nothing here names a companion mod, so the table can be read and tested with no
 * Townstead, no server and no entity.
 *
 * <p>The set is small on purpose. Each constant exists because some MCA: Crime activity genuinely has
 * to stop it, and a constant nothing yields to would be a promise the mod does not keep.
 */
public enum CrimeActivityOperation {

    /**
     * Starting a work behaviour — a station task, a producer recipe, a tool-carrying errand.
     *
     * <p>Start-gating only. A work behaviour that is already running is left to finish; the gate
     * refuses the next start and closes the running one through the behaviour's own stop path. What
     * MCA: Crime cannot do is reconcile a half-finished recipe's staged inputs, so it never tries.
     */
    WORK_START,

    /** Sending the villager to rest: a bed, a berth, a fatigue-recovery route. */
    REST_TRAVEL,

    /** Freezing the villager in place to play a reaction, which takes over their walk target. */
    REACTION_LOCK,

    /** Putting a display item in the villager's hand that is not their own equipment. */
    DISPLAY_TOOL,

    /** Idle social movement: gossip circles, meeting-point wander, schedule drift. */
    SOCIAL_WANDER,

    /**
     * Moving of the villager's own accord at all (0.7.5 §3.3).
     *
     * <p>Broader than {@link #SOCIAL_WANDER}, and the reason it exists: a restrained or held villager
     * does not merely skip the gossip circle, they do not walk anywhere they chose to. This is how a
     * captive is held in place now that the vanilla leash is gone — a claim another system asks
     * permission from, never {@code setNoAi}, which would freeze the villager solid and take their
     * head, their breathing and their despawn rules with it.
     */
    VOLUNTARY_MOVEMENT,

    /**
     * Using or swapping what is in the villager's hands.
     *
     * <p>The NPC counterpart of the arm restraints' {@code useItem}: a villager whose arms are taped
     * does not pick up a tool, eat, or start a hand-held behaviour. Distinct from
     * {@link #DISPLAY_TOOL}, which is somebody else putting a prop in their hand for presentation.
     */
    ITEM_ACTION
}
