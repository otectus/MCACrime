package dev.otectus.mcacrime.action;

/**
 * What an action is aimed at. This decides which menu an action can appear in, and it is the reason
 * the villager interaction menu never offers "Escape" and the captive panel never offers "Mug".
 */
public enum ActionTargetKind {
    /** Aimed at another entity — the villager interaction menu. */
    ENTITY,
    /** Aimed at the actor. Actor and target are the same entity, and the target lock is self-held. */
    SELF
}
