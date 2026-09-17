package dev.otectus.mcacrime.property;

import dev.otectus.mcacrime.facility.TownsteadBuildingRef;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The F01-F08 property rows of the integration plan's behavioural regression matrix (§18.2), as unit
 * tests of the one pure function that decides whether anybody is charged.
 *
 * <p>Each test names its row and asserts the outcome the document requires in its own words. What
 * cannot be asserted here is the detector's half — whether the transfer really happened the way it was
 * reported — which is why every row that turns on detection (F01, F03's throw, F06) is expressed as
 * the input the detector produces in that situation.
 */
class TransferAttributionTest {

    private static final ResourceLocation OVERWORLD =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final BlockPos CHEST = new BlockPos(10, 64, 10);
    private static final int VILLAGE = 3;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000b0b0");
    private static final UUID WORKER = UUID.fromString("00000000-0000-0000-0000-000000004401");

    @AfterEach
    void clearAuthorisations() {
        WorkTransferContext.clearAll();
    }

    private static PropertyPolicy villageStores(PropertyAccessRule rule) {
        return PropertyPolicy.container(OVERWORLD, CHEST,
                new TownsteadBuildingRef(OVERWORLD, VILLAGE, 7, 12), PropertyOwnerKind.VILLAGE, null,
                rule, false, PropertySource.MANUAL, "operator", 100L);
    }

    private static TransferAttribution.CommittedTransfer take(UUID actor, int count, long time,
                                                              int sequence) {
        return new TransferAttribution.CommittedTransfer(actor, PropertyActor.Kind.PLAYER, OVERWORLD,
                CHEST, TransferAttribution.Direction.OUT, "Bread", "minecraft:bread#", count, time,
                sequence);
    }

    private static TransferAttribution judge(TransferAttribution.CommittedTransfer transfer,
                                             @Nullable PropertyPolicy policy, PropertyActor actor,
                                             @Nullable WorkTransferContext work, boolean supported) {
        PropertyAccess.Operation operation = transfer.direction() == TransferAttribution.Direction.IN
                ? PropertyAccess.Operation.PUT : PropertyAccess.Operation.TAKE;
        return TransferAttribution.of(transfer, policy,
                PropertyAccess.decide(policy, actor, operation), work, supported);
    }

    private static PropertyActor player(UUID id) {
        return PropertyActor.player(id, PropertyActor.NO_VILLAGE, false);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * F01 — "Player opens a chest while a cook or hopper removes items." Required: no theft charge
     * against the viewer.
     *
     * <p>The detector produces nothing at all in that situation, because the container lost and the
     * viewer gained nothing, so there is no matched pair. The zero-count transfer below is what a
     * caller that judged it anyway would hand in, and it must still charge nobody.
     */
    @Test
    void f01_aViewerIsNotChargedForALossSomebodyElseCaused() {
        TransferAttribution attribution =
                judge(take(ALICE, 0, 500L, 0), villageStores(PropertyAccessRule.RESIDENTS),
                        player(ALICE), null, true);

        assertEquals(TransferAttribution.Outcome.UNATTRIBUTED, attribution.outcome());
        assertFalse(attribution.charges());
    }

    /**
     * F02 — "Two players transfer items concurrently." Required: each committed transfer is attributed
     * only to its actor.
     */
    @Test
    void f02_concurrentTransfersAreAttributedSeparatelyToTheirOwnActors() {
        PropertyPolicy policy = villageStores(PropertyAccessRule.RESIDENTS);

        TransferAttribution alice = judge(take(ALICE, 3, 500L, 0), policy, player(ALICE), null, true);
        TransferAttribution bob = judge(take(BOB, 2, 500L, 0), policy, player(BOB), null, true);

        assertTrue(alice.charges());
        assertTrue(bob.charges());
        assertNotEquals(alice.transferId(), bob.transferId(),
                "two actors in the same tick must not collapse into one transfer");
        assertNotEquals(alice.groupingKey(), bob.groupingKey(),
                "and must not be grouped into one incident either");
        assertEquals(ALICE, alice.transfer().actor());
        assertEquals(BOB, bob.transfer().actor());
    }

    /**
     * F03 — "Shift-click, hotbar swap, cursor move, throw, drag, double-click." Required: supported
     * actions have correct quantities and one receipt.
     *
     * <p>Every route except the throw reaches the actor's inventory or cursor, so the detector reports
     * the quantity that moved and each observation mints exactly one transfer id — recomputing it from
     * the same observation gives the same id, which is what makes "one receipt" true after a replay.
     * A throw is the documented blind spot: the container loses and nothing of the actor's gains, so it
     * arrives here as an unmatched loss and charges nobody rather than charging a quantity nothing can
     * prove the actor took.
     */
    @Test
    void f03_eachSupportedRouteMintsOneIdForItsOwnQuantity() {
        PropertyPolicy policy = villageStores(PropertyAccessRule.RESIDENTS);

        TransferAttribution first = judge(take(ALICE, 16, 500L, 0), policy, player(ALICE), null, true);
        TransferAttribution again = judge(take(ALICE, 16, 500L, 0), policy, player(ALICE), null, true);
        TransferAttribution secondStack =
                judge(take(ALICE, 16, 500L, 1), policy, player(ALICE), null, true);

        assertEquals(16, first.transfer().count());
        assertEquals(first.transferId(), again.transferId(),
                "the same observation must always mint the same transfer id");
        assertNotEquals(first.transferId(), secondStack.transferId(),
                "two stacks moved in one tick are two transfers, not one");
        assertEquals(first.groupingKey(), secondStack.groupingKey(),
                "§10.5: a continuous action is one bounded incident, not one charge per stack");
    }

    /** F03, the throw route: an unmatched loss is reported, never charged. */
    @Test
    void f03_aThrowFromAContainerSlotIsNotChargedBecauseNothingMatchesIt() {
        TransferAttribution attribution = judge(take(ALICE, 0, 500L, 0),
                villageStores(PropertyAccessRule.RESIDENTS), player(ALICE), null, true);

        assertFalse(attribution.charges(),
                "the detector reports a loss with no matched actor gain; charging it would be a guess");
    }

    /**
     * F04 — "Deposit, donation, permitted work, direct care, accepted delivery." Required: legitimate
     * transfers receive no theft charge.
     */
    @Test
    void f04_everyLegitimateTransferIsUnchargedForItsOwnReason() {
        PropertyPolicy stores = villageStores(PropertyAccessRule.RESIDENTS);

        TransferAttribution deposit = judge(new TransferAttribution.CommittedTransfer(ALICE,
                        PropertyActor.Kind.PLAYER, OVERWORLD, CHEST, TransferAttribution.Direction.IN,
                        "Bread", "minecraft:bread#", 4, 500L, 0), stores, player(ALICE), null, true);
        TransferAttribution publicRation = judge(take(ALICE, 4, 500L, 0),
                villageStores(PropertyAccessRule.PUBLIC), player(ALICE), null, true);
        TransferAttribution residentTaking = judge(take(ALICE, 4, 500L, 0), stores,
                PropertyActor.villager(ALICE, VILLAGE, true, null), null, true);

        assertEquals(TransferAttribution.Outcome.PERMITTED, deposit.outcome());
        assertEquals(TransferAttribution.Outcome.PERMITTED, publicRation.outcome());
        assertEquals(TransferAttribution.Outcome.PERMITTED, residentTaking.outcome());
    }

    /**
     * F05 — "Unknown owner, disputed overlap, unsupported custom menu." Required: no speculative theft
     * charge; the diagnostic identifies the gap.
     */
    @Test
    void f05_anUnknownOwnerOrAnUnsupportedMenuIsReportedRatherThanCharged() {
        TransferAttribution unclaimed =
                judge(take(ALICE, 4, 500L, 0), null, player(ALICE), null, true);
        TransferAttribution unsupported = judge(take(ALICE, 4, 500L, 0),
                villageStores(PropertyAccessRule.OWNER_ONLY), player(ALICE), null, false);

        assertEquals(TransferAttribution.Outcome.UNATTRIBUTED, unclaimed.outcome());
        assertFalse(unclaimed.reason().isBlank(), "the gap has to be nameable");
        assertEquals(TransferAttribution.Outcome.UNSUPPORTED, unsupported.outcome());
        assertFalse(unsupported.charges(),
                "§10.2: an unsupported source is reported as unsupported, not charged to the "
                        + "nearest player");
    }

    /**
     * F06 — "Crash/reconnect during transfer or repayment." Required: reconciliation retains
     * uncertainty and prevents duplicate debit/reward.
     *
     * <p>The half that lives here is the derived id: the same committed transfer judged again after a
     * reconnect produces the same transfer id, so the receipt store recognises it instead of writing a
     * second charge. The other half — an unresolved receipt staying unresolved — is
     * {@code PropertyReceiptReplayTest}.
     */
    @Test
    void f06_thesameTransferJudgedTwiceIsTheSameTransfer() {
        PropertyPolicy policy = villageStores(PropertyAccessRule.RESIDENTS);
        TransferAttribution.CommittedTransfer observed = take(ALICE, 7, 500L, 0);

        assertEquals(TransferAttribution.transferId(observed), TransferAttribution.transferId(observed));
        assertEquals(judge(observed, policy, player(ALICE), null, true).transferId(),
                judge(observed, policy, player(ALICE), null, true).transferId());
    }

    /**
     * F07 — "Property changes owner after theft." Required: the historical victim and the recovery
     * beneficiary remain correct.
     *
     * <p>The attribution stamps the revision the terms were at, and the receipt copies the owner in at
     * the moment of loss. Changing the owner afterwards bumps the revision and leaves the receipt
     * saying exactly who was robbed.
     */
    @Test
    void f07_aLaterOwnerChangeDoesNotRewriteWhoWasRobbed() {
        PropertyPolicy before = PropertyPolicy.container(OVERWORLD, CHEST,
                new TownsteadBuildingRef(OVERWORLD, VILLAGE, 7, 12), PropertyOwnerKind.PLAYER, BOB,
                PropertyAccessRule.OWNER_ONLY, false, PropertySource.MANUAL, "operator", 100L);
        TransferAttribution attribution =
                judge(take(ALICE, 4, 500L, 0), before, player(ALICE), null, true);
        PropertyReceipt receipt = PropertyReceipt.lost(attribution, before, UUID.randomUUID());

        PropertyPolicy after = before.withOwner(PropertyOwnerKind.PLAYER, ALICE, 900L);

        assertEquals(BOB, receipt.ownerAtLoss(), "the receipt remembers who owned it when it was taken");
        assertEquals(before.revision(), receipt.policyRevision());
        assertNotEquals(after.revision(), receipt.policyRevision(),
                "the terms moved on; the history did not");
        assertEquals(ALICE, after.ownerId());
    }

    /**
     * F08 — "Crop/slaughter task performs its authorized work." Required: no player sabotage or
     * livestock-harm incident.
     *
     * <p>The withdrawal half of that row: a settlement task collecting its own inputs from a container
     * it is authorised for is authorised work, and §10.4's "a role label is not a blanket exemption"
     * shows up as the second assertion — the same worker at a container the context does not name is
     * judged like anybody else.
     */
    @Test
    void f08_anAuthorisedTaskWithdrawalIsNeverTheft() {
        PropertyPolicy sealed = villageStores(PropertyAccessRule.FORBIDDEN);
        WorkTransferContext context = new WorkTransferContext(WORKER,
                ResourceLocation.fromNamespaceAndPath("townstead", "harvest"), "seeds",
                UUID.randomUUID(), OVERWORLD, CHEST, 400L, 1600L);
        WorkTransferContext.declareForTest(context);

        TransferAttribution.CommittedTransfer withdrawal = new TransferAttribution.CommittedTransfer(
                WORKER, PropertyActor.Kind.VILLAGER, OVERWORLD, CHEST,
                TransferAttribution.Direction.OUT, "Wheat Seeds", "minecraft:wheat_seeds#", 6, 500L, 0);

        TransferAttribution authorised = judge(withdrawal, sealed,
                PropertyActor.villager(WORKER, VILLAGE, true, "worker"),
                WorkTransferContext.current(WORKER, OVERWORLD, CHEST, 500L).orElse(null), true);

        assertEquals(TransferAttribution.Outcome.AUTHORISED_WORK, authorised.outcome());
        assertFalse(authorised.charges());

        BlockPos elsewhere = new BlockPos(40, 64, 40);
        assertTrue(WorkTransferContext.current(WORKER, OVERWORLD, elsewhere, 500L).isEmpty(),
                "§10.4: the worker, the source and the operation all have to line up");
    }

    @Test
    void anExpiredAuthorisationStopsCoveringAnything() {
        WorkTransferContext.declareForTest(new WorkTransferContext(WORKER,
                ResourceLocation.fromNamespaceAndPath("townstead", "harvest"), "seeds",
                UUID.randomUUID(), OVERWORLD, CHEST, 400L, 600L));

        assertTrue(WorkTransferContext.current(WORKER, OVERWORLD, CHEST, 500L).isPresent());
        assertTrue(WorkTransferContext.current(WORKER, OVERWORLD, CHEST, 900L).isEmpty(),
                "an authorisation that outlived its task would exempt a villager for the rest of the save");
    }

    @Test
    void aWorkContextCannotBeDeclaredWithoutAServerOnItsOwnThread() {
        assertFalse(WorkTransferContext.declare(null, new WorkTransferContext(WORKER,
                        ResourceLocation.fromNamespaceAndPath("townstead", "harvest"), "seeds",
                        UUID.randomUUID(), OVERWORLD, CHEST, 400L, 1600L)),
                "§10.4: only server-side task code may authorise a withdrawal");
        assertTrue(WorkTransferContext.issue(null, WORKER,
                ResourceLocation.fromNamespaceAndPath("townstead", "harvest"), "seeds", OVERWORLD, CHEST,
                100L).isEmpty());
    }

    @Test
    void groupingBoundsAnIncidentRatherThanRunningForever() {
        PropertyPolicy policy = villageStores(PropertyAccessRule.RESIDENTS);

        String early = judge(take(ALICE, 1, 100L, 0), policy, player(ALICE), null, true).groupingKey();
        String sameWindow =
                judge(take(ALICE, 1, 199L, 0), policy, player(ALICE), null, true).groupingKey();
        String laterWindow =
                judge(take(ALICE, 1, 400L, 0), policy, player(ALICE), null, true).groupingKey();

        assertEquals(early, sameWindow);
        assertNotEquals(early, laterWindow,
                "coming back an hour later is a second theft, not a continuation of the first");
    }
}
