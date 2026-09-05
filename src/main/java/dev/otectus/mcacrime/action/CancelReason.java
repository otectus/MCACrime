package dev.otectus.mcacrime.action;

public enum CancelReason {
    ACTOR_GONE, TARGET_GONE, MOVED, OUT_OF_RANGE, LOST_SIGHT, DAMAGED, DEATH, DIMENSION_CHANGED, CONFLICT,
    /** The actor put the weapon away mid-threat, so the threat stopped being one. */
    WEAPON_LOST
}
