package dev.otectus.mcacrime.integration;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.TownsteadReactionEvent;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One outbox, two companion mods, and the rule that keeps their failures apart.
 *
 * <h2>The bug this exists to make impossible</h2>
 *
 * <p>The outbox was built for one companion. When MCA: Reputation gives up on filing a civic incident,
 * MCA: Crime applies its own local village-standing penalty instead — correct, and the compensation for
 * a deed that would otherwise have cost the player nothing publicly. Putting Townstead reactions on the
 * same queue puts them one shared code path away from that penalty, and the resulting bug is
 * particularly nasty: a player loses standing in a village because a villager did not play a wave
 * animation. There is no message, nothing in the ledger, and no way for them to work out what happened
 * — the deed was recorded correctly, the case stands, and the punishment is for an animation.
 *
 * <p>So the routing is a pure function and it is asserted directly here, rather than being a property of
 * whichever branch the pump happens to take.
 */
class IntegrationTargetRoutingTest {

    private static final CrimeCommunityKey RIVERSIDE =
            new CrimeCommunityKey(new ResourceLocation("minecraft", "overworld"), 3);

    /** A Townstead delivery is never MCA: Reputation's, whatever went wrong with it. */
    @Test
    void aTownsteadFailureNeverAppliesAReputationPenalty() {
        for (CrimeIntegrationOperation.Status status : CrimeIntegrationOperation.Status.values()) {
            for (ResourceLocation action : new ResourceLocation[] {
                    IntegrationTargets.ACTION_REACT,
                    IntegrationTargets.ACTION_CREATE,
                    IntegrationTargets.ACTION_RESOLVE}) {
                assertFalse(DeliveryPolicy.appliesLocalVillagePenalty(
                                IntegrationTargets.TOWNSTEAD_REACTION, action, status),
                        "a Townstead delivery must never reach the local village penalty (action="
                                + action + ", status=" + status + ")");
            }
        }
    }

    /** And the penalty that does exist still works, for exactly the one case it is for. */
    @Test
    void aLostCivicRecordStillCountsLocally() {
        assertTrue(DeliveryPolicy.appliesLocalVillagePenalty(
                IntegrationTargets.REPUTATION_RECORD_INCIDENT, IntegrationTargets.ACTION_CREATE,
                CrimeIntegrationOperation.Status.DEAD_LETTER));

        // Not while it is still being retried: the write may yet land, and charging now would charge
        // twice.
        assertFalse(DeliveryPolicy.appliesLocalVillagePenalty(
                IntegrationTargets.REPUTATION_RECORD_INCIDENT, IntegrationTargets.ACTION_CREATE,
                CrimeIntegrationOperation.Status.PENDING));
        assertFalse(DeliveryPolicy.appliesLocalVillagePenalty(
                IntegrationTargets.REPUTATION_RECORD_INCIDENT, IntegrationTargets.ACTION_CREATE,
                CrimeIntegrationOperation.Status.COMPLETE));

        // And not for a resolution: the crime behind it was already recorded, so there is nothing
        // uncompensated to compensate for.
        assertFalse(DeliveryPolicy.appliesLocalVillagePenalty(
                IntegrationTargets.REPUTATION_RESOLVE_INCIDENT, IntegrationTargets.ACTION_RESOLVE,
                CrimeIntegrationOperation.Status.DEAD_LETTER));
    }

    /** Routing is by namespace, so a target added later is routed by construction. */
    @Test
    void targetsRouteByNamespace() {
        assertTrue(IntegrationTargets.isTownstead(IntegrationTargets.TOWNSTEAD_REACTION));
        assertFalse(IntegrationTargets.isReputation(IntegrationTargets.TOWNSTEAD_REACTION));

        assertTrue(IntegrationTargets.isReputation(IntegrationTargets.REPUTATION_RECORD_INCIDENT));
        assertTrue(IntegrationTargets.isReputation(IntegrationTargets.REPUTATION_RESOLVE_INCIDENT));
        assertFalse(IntegrationTargets.isTownstead(IntegrationTargets.REPUTATION_RECORD_INCIDENT));

        assertTrue(IntegrationTargets.isTownstead(new ResourceLocation("townstead", "something_later")));
        assertFalse(IntegrationTargets.isTownstead(null));
        assertFalse(IntegrationTargets.isReputation(null));
        assertFalse(IntegrationTargets.isTownstead(new ResourceLocation("mcacrime", "react")));
    }

    /**
     * The same thing happening produces the same key, and the same operation, forever.
     *
     * <p>This is what makes a crash-replayed queue, a relog and an operator retry produce one reaction
     * between them. A key that folded in a timestamp would look unique on every replay, which is
     * precisely the property an idempotency key must not have.
     */
    @Test
    void oneEventOnOneCaseIsOneOperation() {
        UUID caseId = UUID.nameUUIDFromBytes("case".getBytes());
        String first = TownsteadReactions.idempotencyKey(TownsteadReactionEvent.CRIME_WITNESSED, caseId);
        String second = TownsteadReactions.idempotencyKey(TownsteadReactionEvent.CRIME_WITNESSED, caseId);
        assertEquals(first, second);
        assertEquals(TownsteadReactions.operationIdFor(first), TownsteadReactions.operationIdFor(second));
        assertTrue(first.contains(caseId.toString()), first);
        assertTrue(first.startsWith(TownsteadReactionEvent.CRIME_WITNESSED.id()), first);
    }

    /** Different events on the same case, and the same event on different cases, stay distinct. */
    @Test
    void differentThingsGetDifferentKeys() {
        UUID first = UUID.nameUUIDFromBytes("first".getBytes());
        UUID second = UUID.nameUUIDFromBytes("second".getBytes());

        Set<UUID> ids = new HashSet<>();
        for (TownsteadReactionEvent event : TownsteadReactionEvent.values()) {
            for (UUID subject : new UUID[] {first, second}) {
                assertTrue(ids.add(TownsteadReactions.operationIdFor(
                                TownsteadReactions.idempotencyKey(event, subject))),
                        "two distinct reactions collided on one operation id: " + event.id() + "/" + subject);
            }
        }
        assertEquals(TownsteadReactionEvent.values().length * 2, ids.size());

        assertNotEquals(
                TownsteadReactions.idempotencyKey(TownsteadReactionEvent.CUSTODY_STARTED, first),
                TownsteadReactions.idempotencyKey(TownsteadReactionEvent.CUSTODY_ENDED, first));
    }

    /**
     * The rate cap, which is the other half of "a village does not spend a minute reacting".
     *
     * <p>Idempotency stops one event producing many reactions; it does nothing about many events. A
     * spree, a raid, or an operator testing the arrest command twenty times each produce one legitimate
     * reaction apiece, and the cap is what stops the village doing nothing else.
     */
    @Test
    void oneCommunityGetsABoundedNumberOfReactionsPerWindow() {
        TownsteadReactions.clearAll();
        long now = 1_000L;
        for (int i = 0; i < TownsteadReactions.MAX_PER_WINDOW; i++) {
            assertTrue(TownsteadReactions.allow(RIVERSIDE, now + i),
                    "the first " + TownsteadReactions.MAX_PER_WINDOW + " must be allowed");
        }
        assertFalse(TownsteadReactions.allow(RIVERSIDE, now + TownsteadReactions.MAX_PER_WINDOW),
                "the cap must actually stop something");

        // A different settlement has its own budget: one village having a bad minute must not silence
        // the one next door.
        CrimeCommunityKey hilltop = new CrimeCommunityKey(new ResourceLocation("minecraft", "overworld"), 4);
        assertTrue(TownsteadReactions.allow(hilltop, now));

        // And the window reopens.
        assertTrue(TownsteadReactions.allow(RIVERSIDE, now + TownsteadReactions.RATE_WINDOW_TICKS));
        TownsteadReactions.clearAll();
    }

    /** A clock that went backwards reopens the window rather than locking a village out of it. */
    @Test
    void aBackwardsClockDoesNotSilenceASettlement() {
        TownsteadReactions.clearAll();
        for (int i = 0; i < TownsteadReactions.MAX_PER_WINDOW; i++) {
            TownsteadReactions.allow(RIVERSIDE, 10_000L);
        }
        assertFalse(TownsteadReactions.allow(RIVERSIDE, 10_000L));
        assertTrue(TownsteadReactions.allow(RIVERSIDE, 5L),
                "a world whose game time went backwards must not leave a village permanently capped");
        TownsteadReactions.clearAll();
    }
}
