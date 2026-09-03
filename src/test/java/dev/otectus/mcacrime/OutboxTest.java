package dev.otectus.mcacrime;

import dev.otectus.mcacrime.integration.CrimeIntegrationHooks;
import dev.otectus.mcacrime.integration.CrimeIntegrationOperation;
import dev.otectus.mcacrime.integration.DeliveryOutcome;
import dev.otectus.mcacrime.integration.DeliveryPolicy;
import dev.otectus.mcacrime.integration.IntegrationTargets;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable queue that makes a cross-mod write survive a crash, and the policy that decides when to
 * stop trying.
 */
class OutboxTest {

    private static final int MAX_ATTEMPTS = 6;

    private static CrimeIntegrationOperation operation(long gameTime) {
        CompoundTag payload = new CompoundTag();
        payload.putString(IntegrationTargets.PAYLOAD_INCIDENT_TYPE, "mcareputation:villager_assaulted");
        payload.putString(IntegrationTargets.PAYLOAD_DEDUPE_KEY, "crime:" + UUID.randomUUID());
        return CrimeIntegrationOperation.create(UUID.randomUUID(),
                IntegrationTargets.REPUTATION_RECORD_INCIDENT, UUID.randomUUID(), UUID.randomUUID(),
                IntegrationTargets.ACTION_CREATE, payload, gameTime);
    }

    // ------------------------------------------------------------------ persistence

    @Test
    void anOperationRoundTripsThroughNbt() {
        CrimeIntegrationOperation original = operation(1234L);
        CrimeIntegrationOperation loaded = CrimeIntegrationOperation.load(original.save());

        assertNotNull(loaded);
        assertEquals(original, loaded);
    }

    @Test
    void aMalformedOperationIsSkippedRatherThanThrowing() {
        assertEquals(null, CrimeIntegrationOperation.load(new CompoundTag()));
    }

    /** Pending work must survive the restart it exists to protect against. */
    @Test
    void queuedWorkSurvivesSaveAndLoad() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeIntegrationOperation pending = operation(100L);
        assertTrue(data.enqueueOperation(pending));

        CrimeWorldData reloaded = CrimeWorldData.load(data.save(new CompoundTag()));

        assertEquals(1, reloaded.pendingOperationCount());
        assertEquals(pending, reloaded.dueOperations(200L, 10).get(0));
    }

    @Test
    void enqueueingTheSameOperationTwiceIsHarmless() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeIntegrationOperation pending = operation(100L);

        assertTrue(data.enqueueOperation(pending));
        assertTrue(data.enqueueOperation(pending), "a replayed enqueue reports success, not failure");
        assertEquals(1, data.pendingOperationCount());
    }

    @Test
    void completingAnOperationRemovesItFromTheQueue() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeIntegrationOperation pending = operation(100L);
        data.enqueueOperation(pending);

        data.updateOperation(pending.complete());

        assertEquals(0, data.pendingOperationCount());
        assertEquals(0, data.deadLetterCount());
    }

    @Test
    void aDeadLetterIsRetainedForInspection() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeIntegrationOperation pending = operation(100L);
        data.enqueueOperation(pending);

        data.updateOperation(pending.deadLetter("UNKNOWN_TARGET"));

        assertEquals(0, data.pendingOperationCount());
        assertEquals(1, data.deadLetterCount());
        assertEquals("UNKNOWN_TARGET", data.deadLetters().get(0).lastError());
    }

    @Test
    void aDeadLetterCanBeRevivedByAnOperator() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeIntegrationOperation pending = operation(100L);
        data.enqueueOperation(pending);
        data.updateOperation(pending.deadLetter("UNKNOWN_TARGET"));

        assertTrue(data.reviveDeadLetter(pending.operationId(), 500L));

        assertEquals(1, data.pendingOperationCount());
        assertEquals(0, data.deadLetterCount());
    }

    @Test
    void onlyDueOperationsAreOffered() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeIntegrationOperation pending = operation(100L);
        data.enqueueOperation(pending);
        data.updateOperation(pending.withAttempt(1000L, "TRANSIENT_FAILURE"));

        assertTrue(data.dueOperations(500L, 10).isEmpty(), "still backing off");
        assertEquals(1, data.dueOperations(1000L, 10).size());
    }

    @Test
    void theDeliveryBudgetIsRespected() {
        CrimeWorldData data = new CrimeWorldData();
        for (int i = 0; i < 10; i++) {
            data.enqueueOperation(operation(i));
        }

        assertEquals(3, data.dueOperations(1000L, 3).size());
    }

    // ------------------------------------------------------------------ retry policy

    @Test
    void backoffGrowsExponentiallyAndThenStops() {
        assertEquals(200L, DeliveryPolicy.nextAttemptTime(0, 0L, 200L, 24000L));
        assertEquals(400L, DeliveryPolicy.nextAttemptTime(1, 0L, 200L, 24000L));
        assertEquals(800L, DeliveryPolicy.nextAttemptTime(2, 0L, 200L, 24000L));
        assertEquals(24000L, DeliveryPolicy.nextAttemptTime(30, 0L, 200L, 24000L),
                "the delay is capped, so a stuck operation is still retried eventually");
    }

    @Test
    void successRetiresTheOperation() {
        assertEquals(CrimeIntegrationOperation.Status.COMPLETE,
                DeliveryPolicy.classify(DeliveryOutcome.SUCCESS, 0, MAX_ATTEMPTS));
        assertEquals(CrimeIntegrationOperation.Status.COMPLETE,
                DeliveryPolicy.classify(DeliveryOutcome.ALREADY_DONE, 5, MAX_ATTEMPTS));
    }

    /** Retrying a payload the companion rejects buries the one line that would explain it. */
    @Test
    void anUnretryableFailureDeadLettersImmediately() {
        assertEquals(CrimeIntegrationOperation.Status.DEAD_LETTER,
                DeliveryPolicy.classify(DeliveryOutcome.UNKNOWN_TARGET, 0, MAX_ATTEMPTS));
        assertEquals(CrimeIntegrationOperation.Status.DEAD_LETTER,
                DeliveryPolicy.classify(DeliveryOutcome.INVALID, 0, MAX_ATTEMPTS));
    }

    @Test
    void aTransientFailureRetriesUntilTheBudgetRunsOut() {
        assertEquals(CrimeIntegrationOperation.Status.PENDING,
                DeliveryPolicy.classify(DeliveryOutcome.TRANSIENT_FAILURE, 0, MAX_ATTEMPTS));
        assertEquals(CrimeIntegrationOperation.Status.PENDING,
                DeliveryPolicy.classify(DeliveryOutcome.TRANSIENT_FAILURE, MAX_ATTEMPTS - 2, MAX_ATTEMPTS));
        assertEquals(CrimeIntegrationOperation.Status.DEAD_LETTER,
                DeliveryPolicy.classify(DeliveryOutcome.TRANSIENT_FAILURE, MAX_ATTEMPTS - 1, MAX_ATTEMPTS));
    }

    /**
     * A player who uninstalls a companion for a week must not come back to find everything
     * dead-lettered by a countdown that measured their absence rather than any real failure.
     */
    @Test
    void anAbsentCompanionDoesNotBurnTheAttemptBudget() {
        assertFalse(DeliveryPolicy.countsAgainstBudget(DeliveryOutcome.UNAVAILABLE));
        assertTrue(DeliveryPolicy.countsAgainstBudget(DeliveryOutcome.TRANSIENT_FAILURE));
        assertEquals(CrimeIntegrationOperation.Status.PENDING,
                DeliveryPolicy.classify(DeliveryOutcome.UNAVAILABLE, 0, MAX_ATTEMPTS));
    }

    // ------------------------------------------------------------------ dedupe keys

    @Test
    void aCaseAlwaysProducesTheSameDedupeKey() {
        UUID recordId = UUID.randomUUID();

        assertEquals("crime:" + recordId, CrimeIntegrationHooks.dedupeKeyFor(recordId));
        assertEquals(CrimeIntegrationHooks.dedupeKeyFor(recordId),
                CrimeIntegrationHooks.dedupeKeyFor(recordId));
    }

    /** A case fined and later corrected is two transactions, not one that looks replayed. */
    @Test
    void eachResolutionRevisionIsADistinctTransaction() {
        UUID recordId = UUID.randomUUID();

        assertEquals("crime-resolution:" + recordId + ":1",
                CrimeIntegrationHooks.resolutionDedupeKeyFor(recordId, 1L));
        assertFalse(CrimeIntegrationHooks.resolutionDedupeKeyFor(recordId, 1L)
                .equals(CrimeIntegrationHooks.resolutionDedupeKeyFor(recordId, 2L)));
    }

    // ------------------------------------------------------------------ double-consequence suppression

    /**
     * The truth table behind "one deed, one public consequence". Getting any row wrong means the
     * player is either charged twice or not at all.
     */
    @Test
    void theLocalPenaltyIsSuppressedOnlyWhenSomebodyElseWillRecordTheDeed() {
        // The companion is recording it: skip ours.
        assertTrue(CrimeIntegrationHooks.willRecordCanonically(
                CrimeIds.HARM_VILLAGER, true, true, true, true));

        // Integration off, bridge down, or suppression disabled: ours is the only record there is.
        assertFalse(CrimeIntegrationHooks.willRecordCanonically(
                CrimeIds.HARM_VILLAGER, false, true, true, true));
        assertFalse(CrimeIntegrationHooks.willRecordCanonically(
                CrimeIds.HARM_VILLAGER, true, true, false, true));
        assertFalse(CrimeIntegrationHooks.willRecordCanonically(
                CrimeIds.HARM_VILLAGER, true, false, true, true));
    }

    /**
     * The subtle row. Without the authority claim MCA: Reputation is still detecting assault itself,
     * so we will not record it — and must not suppress our local penalty on the strength of a write
     * we are not going to make.
     */
    @Test
    void anOverlappingDeedNeedsTheAuthorityClaimBeforeSuppressing() {
        assertFalse(CrimeIntegrationHooks.willRecordCanonically(
                CrimeIds.HARM_VILLAGER, true, true, true, false));
        assertFalse(CrimeIntegrationHooks.willRecordCanonically(
                CrimeIds.KILL_VILLAGER, true, true, true, false));
    }

    /** A deed only we detect needs no claim — nobody else was ever going to record it. */
    @Test
    void aCrimeOnlyThisModDetectsDoesNotNeedAuthority() {
        assertTrue(CrimeIntegrationHooks.willRecordCanonically(
                CrimeIds.THEFT, true, true, true, false));
        assertTrue(CrimeIntegrationHooks.willRecordCanonically(
                CrimeIds.KIDNAP, true, true, true, false));
    }
}
