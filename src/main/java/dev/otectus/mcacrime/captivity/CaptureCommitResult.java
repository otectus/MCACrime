package dev.otectus.mcacrime.captivity;

/**
 * Why a capture did or did not stand.
 *
 * <p>Every one of these used to be {@code false}. A boolean is enough to decide whether to write the
 * record and nothing else: the caller could not tell "somebody else got there first" from "you are
 * already holding as many people as you are allowed to", so it could neither say which happened nor
 * decide whether the restraint in the captor's hand should be spent. Naming the reasons is what makes
 * {@link CaptureTicker}'s commit sequence able to consume an item only on {@link #CAPTURED}, and what
 * lets {@code ArrestService} abort an arrest whose custody could not be installed.
 *
 * <p>Names, never ordinals, if one of these is ever persisted.
 */
public enum CaptureCommitResult {
    /** The record was written. The only value for which anything downstream may run. */
    CAPTURED,
    /** Somebody — possibly the same captor — already holds this captive, lawfully or not. */
    ALREADY_HELD,
    /** The captor is at their unlawful-captive allowance. */
    QUOTA_FULL,
    /** No captive, no captor, the captor's own uuid, a dead target, or a target in another world. */
    TARGET_INVALID,
    /** The restraint the channel was started with is no longer in the captor's inventory. */
    RESTRAINT_MISSING,
    /** The actor/target lease the channel held was released or taken by something else. */
    SESSION_LOST,
    /** Refused by config, by state the caller owns, or by a gate above the custody table. */
    GATED;

    /** Whether the capture stands. */
    public boolean ok() {
        return this == CAPTURED;
    }
}
