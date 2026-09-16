package dev.otectus.mcacrime.integration;

import dev.otectus.mcacrime.compat.ReputationDelivery;

/**
 * What happened when the pump tried to hand one operation to a companion mod.
 *
 * <p>The distinction that matters is <b>retry or not</b>. A companion being absent is a delay: it may
 * come back, and the work is still owed. A payload the companion rejects as meaningless is not a
 * delay — retrying it a thousand times will produce the same answer while burying the one log line
 * that would have told an operator their datapack is missing a definition.
 *
 * <p>The values map one-to-one onto {@link ReputationDelivery.Outcome} through {@link #forCreate} and
 * {@link #forResolve}, which is the whole reason that type exists. The mapping used to be a guess made
 * from an empty {@code Optional}: no incident id meant {@code UNKNOWN_TARGET} when the authority was
 * held and {@code UNAVAILABLE} when it was not, so an unwitnessed deed the companion legitimately kept
 * private was dead-lettered as a missing datapack definition, and a full ledger — a condition that
 * clears itself as entries decay — was reported as the companion being uninstalled.
 */
public enum DeliveryOutcome {

    /** Delivered. */
    SUCCESS,
    /** The companion had already done this, which counts as success. */
    ALREADY_DONE,
    /**
     * Accepted, and deliberately not public: an unwitnessed deed whose definition keeps a private
     * record. The work is finished — there is simply no public incident to link, so no later
     * resolution can move one. Not a failure, and emphatically not "no crime occurred": the case, the
     * Heat and the sentence are exactly where they were.
     */
    ACCEPTED_NO_PUBLIC_RECORD,
    /** No bridge right now — mod absent, disabled, or still starting. Worth retrying. */
    UNAVAILABLE,
    /** Reachable but it failed this time. Worth retrying with backoff. */
    TRANSIENT_FAILURE,
    /**
     * The companion's ledger for that village is full and cannot admit another incident yet. Worth
     * retrying, because space comes back as entries decay — and never to be read as the crime not
     * having happened.
     */
    REFUSED_CAPACITY,
    /**
     * A resolution arrived before the incident it settles had been linked. Worth retrying: the create
     * for the same case is normally a few ticks behind in the same queue. It counts against the attempt
     * budget, so a resolution whose create dead-lettered does not retry forever.
     */
    AWAITING_LINK,
    /** The companion does not recognise what we asked for. Retrying cannot help. */
    UNKNOWN_TARGET,
    /** The payload is malformed or no longer makes sense. Retrying cannot help. */
    INVALID;

    /** Whether the work is done and the operation may be retired. */
    public boolean successful() {
        return this == SUCCESS || this == ALREADY_DONE || this == ACCEPTED_NO_PUBLIC_RECORD;
    }

    /** Whether trying again could ever produce a different answer. */
    public boolean retryable() {
        return this == UNAVAILABLE || this == TRANSIENT_FAILURE || this == REFUSED_CAPACITY
                || this == AWAITING_LINK;
    }

    /**
     * The outcome of a civic write that creates an incident.
     *
     * <p>{@code REFUSED_DISABLED} becomes {@code UNAVAILABLE} rather than a failure: the companion is
     * installed and healthy and an operator has simply switched its integration off, which is the same
     * shape of delay as the mod not being loaded and must not burn the attempt budget either.
     */
    public static DeliveryOutcome forCreate(ReputationDelivery.Outcome outcome) {
        if (outcome == null) {
            return TRANSIENT_FAILURE;
        }
        return switch (outcome) {
            case ACCEPTED -> SUCCESS;
            case ACCEPTED_NO_PUBLIC_INCIDENT -> ACCEPTED_NO_PUBLIC_RECORD;
            case DUPLICATE -> ALREADY_DONE;
            case REFUSED_CAPACITY -> REFUSED_CAPACITY;
            case REFUSED_DISABLED, UNAVAILABLE -> UNAVAILABLE;
            case REFUSED_INVALID -> INVALID;
            case UNKNOWN_INCIDENT_TYPE, MISSING_INCIDENT -> UNKNOWN_TARGET;
            case UNKNOWN -> TRANSIENT_FAILURE;
        };
    }

    /**
     * The outcome of a write that moves an existing incident to a resolved status.
     *
     * <p>Two rows differ from {@link #forCreate}. A status that is already in place or stronger is
     * {@code ALREADY_DONE} — monotonic strength is the idempotency mechanism here, so a replayed
     * resolution has to read as success or the outbox would retry it forever. And an incident that is
     * no longer there is terminal rather than retryable: retention dropped it or a later deed absorbed
     * it, and neither is repaired by asking again.
     */
    public static DeliveryOutcome forResolve(ReputationDelivery.Outcome outcome) {
        if (outcome == null) {
            return TRANSIENT_FAILURE;
        }
        return switch (outcome) {
            case ACCEPTED -> SUCCESS;
            case DUPLICATE, ACCEPTED_NO_PUBLIC_INCIDENT -> ALREADY_DONE;
            case REFUSED_CAPACITY -> REFUSED_CAPACITY;
            case REFUSED_DISABLED, UNAVAILABLE -> UNAVAILABLE;
            case REFUSED_INVALID -> INVALID;
            case UNKNOWN_INCIDENT_TYPE, MISSING_INCIDENT -> UNKNOWN_TARGET;
            case UNKNOWN -> TRANSIENT_FAILURE;
        };
    }
}
