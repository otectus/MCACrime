package dev.otectus.mcacrime.integration;

/**
 * What happened when the pump tried to hand one operation to a companion mod.
 *
 * <p>The distinction that matters is <b>retry or not</b>. A companion being absent is a delay: it may
 * come back, and the work is still owed. A payload the companion rejects as meaningless is not a
 * delay — retrying it a thousand times will produce the same answer while burying the one log line
 * that would have told an operator their datapack is missing a definition.
 */
public enum DeliveryOutcome {

    /** Delivered. */
    SUCCESS,
    /** The companion had already done this, which counts as success. */
    ALREADY_DONE,
    /** No bridge right now — mod absent, disabled, or still starting. Worth retrying. */
    UNAVAILABLE,
    /** Reachable but it failed this time. Worth retrying with backoff. */
    TRANSIENT_FAILURE,
    /** The companion does not recognise what we asked for. Retrying cannot help. */
    UNKNOWN_TARGET,
    /** The payload is malformed or no longer makes sense. Retrying cannot help. */
    INVALID;

    /** Whether the work is done and the operation may be retired. */
    public boolean successful() {
        return this == SUCCESS || this == ALREADY_DONE;
    }

    /** Whether trying again could ever produce a different answer. */
    public boolean retryable() {
        return this == UNAVAILABLE || this == TRANSIENT_FAILURE;
    }
}
