package dev.otectus.mcacrime.job;

/**
 * Why an occupation transition did not commit — one constant per genuinely distinct cause.
 *
 * <p>Typed rather than a message string because three different callers act on these: the sweep skips
 * the villager without spending the village cooldown, {@code /crime job} tells the operator what to
 * fix, and {@link ThiefOccupationLifecycle} decides whether to retry, suspend or give up. A string
 * would have all three matching on prose.
 */
public enum OccupationTransitionReason {

    /** Committed, or a no-op that left the world in the requested state. */
    OK,
    /** Not the server thread, or the server is shutting down / not accepting mutations. */
    NOT_MUTABLE,
    /** No loaded entity for this id. The request may be recorded as pending, never as active. */
    NOT_LOADED,
    /** The entity is not an MCA villager, or is not an adult. */
    NOT_ELIGIBLE,
    /** Guard, archer or a configured responder — the S1 rule, restated at this boundary. */
    RESPONDER,
    /** MCA could not be asked, so the role is unknown rather than acceptable. */
    CLASSIFICATION_UNAVAILABLE,
    /** One or more members of the thief-occupation capability bundle did not resolve. */
    CAPABILITY_MISSING,
    /** MCA marks the profession as one it must not lose, or the villager is a temporary occupant. */
    PROTECTED_NPC,
    /** The villager already holds another profession and this route may not overwrite one. */
    ALREADY_EMPLOYED,
    /** The feature is switched off in config. */
    DISABLED,
    /** A station claim was required and none could be reserved or adopted. */
    NO_WORKSITE,
    /** The reservation was lost, taken by somebody else, or timed out before arrival. */
    WORKSITE_LOST,
    /** The persisted record store is at its capacity cap. */
    RECORD_CAPACITY,
    /** The villager is in custody, or otherwise mid-transaction elsewhere. */
    BUSY,
    /** MCA's setter ran but the profession did not read back, so the change was rolled back. */
    VERIFICATION_FAILED,
    /** A mutation threw. What had already been applied was rolled back; see the diagnostic. */
    MUTATION_FAILED,
    /** Rollback itself could not restore the captured state; the occupation is suspended. */
    ROLLBACK_INCOMPLETE,
    /** Another transition committed against this villager while this one was deciding. */
    GENERATION_CONFLICT
}
