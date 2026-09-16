package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.ReputationDelivery;
import dev.otectus.mcacrime.integration.DeliveryOutcome;
import dev.otectus.mcacrime.integration.DeliveryPolicy;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a companion's answer becomes an outbox decision.
 *
 * <p>Before 0.7.3 the pump had six answers and one bit to read them with: an empty {@code Optional}.
 * It guessed {@code UNKNOWN_TARGET} when the authority was held and {@code UNAVAILABLE} when it was
 * not, which meant an unwitnessed deed the companion legitimately kept private was dead-lettered as a
 * missing datapack definition, and a full village ledger — a condition that clears itself as entries
 * decay — was reported as the companion being uninstalled. These tests pin the mapping so neither
 * guess can come back, and they run with MCA: Reputation absent from the classpath, which is exactly
 * how the outcome vocabulary is able to exist on this side of the seam.
 */
class ReputationDeliveryMappingTest {

    private static final int MAX_ATTEMPTS = 6;

    // ------------------------------------------------------------------ create

    @Test
    void anAppliedDeedIsSuccess() {
        assertEquals(DeliveryOutcome.SUCCESS,
                DeliveryOutcome.forCreate(ReputationDelivery.Outcome.ACCEPTED));
    }

    /**
     * The row the old mapping got most wrong. An unwitnessed deed is accepted and kept privately: the
     * work is finished, there is nothing public to link, and nothing about the legal case changes.
     */
    @Test
    void anAcceptedPrivateDeedIsFinishedRatherThanAnUnknownTarget() {
        DeliveryOutcome outcome =
                DeliveryOutcome.forCreate(ReputationDelivery.Outcome.ACCEPTED_NO_PUBLIC_INCIDENT);

        assertEquals(DeliveryOutcome.ACCEPTED_NO_PUBLIC_RECORD, outcome);
        assertTrue(outcome.successful(), "an accepted private record must retire the operation");
        assertFalse(outcome.retryable());
        assertEquals(CrimeIntegrationOperationStatus.COMPLETE,
                CrimeIntegrationOperationStatus.of(DeliveryPolicy.classify(outcome, 0, MAX_ATTEMPTS)));
    }

    @Test
    void aDuplicateIsAlreadyDone() {
        assertEquals(DeliveryOutcome.ALREADY_DONE,
                DeliveryOutcome.forCreate(ReputationDelivery.Outcome.DUPLICATE));
    }

    /**
     * A full ledger is a delay, never "no crime occurred". It is retryable because space comes back as
     * entries decay, and it counts against the attempt budget because it is a real answer from a
     * reachable companion rather than the companion being away.
     */
    @Test
    void aFullLedgerIsRetryableAndNeverTerminal() {
        DeliveryOutcome outcome = DeliveryOutcome.forCreate(ReputationDelivery.Outcome.REFUSED_CAPACITY);

        assertEquals(DeliveryOutcome.REFUSED_CAPACITY, outcome);
        assertFalse(outcome.successful());
        assertTrue(outcome.retryable());
        assertTrue(DeliveryPolicy.countsAgainstBudget(outcome));
    }

    /** An operator switching the companion's own integration off is a delay, like absence. */
    @Test
    void aDisabledCompanionIsUnavailableAndDoesNotBurnTheBudget() {
        DeliveryOutcome outcome = DeliveryOutcome.forCreate(ReputationDelivery.Outcome.REFUSED_DISABLED);

        assertEquals(DeliveryOutcome.UNAVAILABLE, outcome);
        assertFalse(DeliveryPolicy.countsAgainstBudget(outcome));
    }

    @Test
    void anUnknownDefinitionIsTerminalAndSaysSo() {
        DeliveryOutcome outcome =
                DeliveryOutcome.forCreate(ReputationDelivery.Outcome.UNKNOWN_INCIDENT_TYPE);

        assertEquals(DeliveryOutcome.UNKNOWN_TARGET, outcome);
        assertFalse(outcome.retryable());
    }

    @Test
    void anInvalidPayloadIsTerminalAndALostAnswerIsNot() {
        assertEquals(DeliveryOutcome.INVALID,
                DeliveryOutcome.forCreate(ReputationDelivery.Outcome.REFUSED_INVALID));
        assertEquals(DeliveryOutcome.TRANSIENT_FAILURE,
                DeliveryOutcome.forCreate(ReputationDelivery.Outcome.UNKNOWN));
        assertTrue(DeliveryOutcome.forCreate(ReputationDelivery.Outcome.UNKNOWN).retryable());
    }

    @Test
    void aNullOutcomeIsTreatedAsALostAnswerRatherThanSuccess() {
        assertEquals(DeliveryOutcome.TRANSIENT_FAILURE, DeliveryOutcome.forCreate(null));
        assertEquals(DeliveryOutcome.TRANSIENT_FAILURE, DeliveryOutcome.forResolve(null));
    }

    /** Every outcome the adapter can produce has to map to something; a new one must not slip past. */
    @Test
    void everyCompanionOutcomeIsMappedBothWays() {
        for (ReputationDelivery.Outcome outcome : ReputationDelivery.Outcome.values()) {
            assertTrue(DeliveryOutcome.forCreate(outcome) != null, outcome + " has no create mapping");
            assertTrue(DeliveryOutcome.forResolve(outcome) != null, outcome + " has no resolve mapping");
        }
    }

    // ------------------------------------------------------------------ resolve

    /**
     * A status already in place or stronger has to read as done. Monotonic strength is the idempotency
     * mechanism for a resolution, so anything else would retry the settlement forever.
     */
    @Test
    void aReplayedSettlementIsDone() {
        assertEquals(DeliveryOutcome.ALREADY_DONE,
                DeliveryOutcome.forResolve(ReputationDelivery.Outcome.DUPLICATE));
        assertEquals(DeliveryOutcome.ALREADY_DONE,
                DeliveryOutcome.forResolve(ReputationDelivery.Outcome.ACCEPTED_NO_PUBLIC_INCIDENT));
    }

    /** An incident that retention dropped or a later deed absorbed cannot be resolved by trying again. */
    @Test
    void aMissingIncidentIsTerminalOnAResolve() {
        DeliveryOutcome outcome = DeliveryOutcome.forResolve(ReputationDelivery.Outcome.MISSING_INCIDENT);

        assertEquals(DeliveryOutcome.UNKNOWN_TARGET, outcome);
        assertFalse(outcome.retryable());
    }

    // ------------------------------------------------------------------ the awaiting-link row

    /**
     * A settlement can reach the pump before the create that files the incident it settles. Waiting is
     * correct; waiting forever is not, so the wait counts against the attempt budget and eventually
     * dead-letters with its own name in the log.
     */
    @Test
    void aResolutionWaitingForItsLinkRetriesButNotForever() {
        DeliveryOutcome outcome = DeliveryOutcome.AWAITING_LINK;

        assertFalse(outcome.successful());
        assertTrue(outcome.retryable());
        assertTrue(DeliveryPolicy.countsAgainstBudget(outcome));
        assertEquals(CrimeIntegrationOperationStatus.DEAD_LETTER,
                CrimeIntegrationOperationStatus.of(
                        DeliveryPolicy.classify(outcome, MAX_ATTEMPTS - 1, MAX_ATTEMPTS)));
    }

    // ------------------------------------------------------------------ the value type

    @Test
    void theCompanionAnswerCarriesTheIncidentIdWhenThereIsOne() {
        UUID incident = UUID.randomUUID();
        ReputationDelivery applied =
                ReputationDelivery.of(ReputationDelivery.Outcome.ACCEPTED, incident, "applied");

        assertEquals(incident, applied.incidentId().orElseThrow());
        assertTrue(applied.settled());
        assertTrue(ReputationDelivery.unavailable("no bridge").incidentId().isEmpty());
        assertFalse(ReputationDelivery.unknown("threw").settled());
    }

    @Test
    void aNullOutcomeOnTheValueTypeDegradesToUnknown() {
        assertEquals(ReputationDelivery.Outcome.UNKNOWN,
                new ReputationDelivery(null, null, null).outcome());
        assertEquals("", new ReputationDelivery(null, null, null).detail());
    }

    /** The three answers that finish an operation, and only those three. */
    @Test
    void exactlyTheAcceptedRowsAreSettled() {
        Set<ReputationDelivery.Outcome> settled = EnumSet.noneOf(ReputationDelivery.Outcome.class);
        for (ReputationDelivery.Outcome outcome : ReputationDelivery.Outcome.values()) {
            if (outcome.settled()) {
                settled.add(outcome);
            }
        }
        assertEquals(EnumSet.of(ReputationDelivery.Outcome.ACCEPTED,
                ReputationDelivery.Outcome.ACCEPTED_NO_PUBLIC_INCIDENT,
                ReputationDelivery.Outcome.DUPLICATE), settled);
    }

    /**
     * A tiny shim so the assertions above can name a status without importing the operation type's
     * nested enum in every line.
     */
    private enum CrimeIntegrationOperationStatus {
        PENDING, COMPLETE, DEAD_LETTER;

        static CrimeIntegrationOperationStatus of(
                dev.otectus.mcacrime.integration.CrimeIntegrationOperation.Status status) {
            return valueOf(status.name());
        }
    }
}
