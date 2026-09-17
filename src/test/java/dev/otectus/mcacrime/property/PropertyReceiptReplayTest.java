package dev.otectus.mcacrime.property;

import dev.otectus.mcacrime.facility.TownsteadBuildingRef;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay and restitution: one committed transfer is one receipt however many times it is seen, and a
 * returned lot settles exactly once.
 *
 * <p>Both halves of §10.2's reconciliation rule are here. A crash or a reconnect can make the same
 * transfer arrive twice, and the durable id is what turns the second arrival into a no-op rather than
 * a second charge. And §10.6's "prevent repeated turn-ins against the same lot" is the same property
 * from the other side: handing the bread back twice settles one loss.
 */
class PropertyReceiptReplayTest {

    private static final ResourceLocation OVERWORLD =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final BlockPos CHEST = new BlockPos(10, 64, 10);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

    private static CrimeWorldData store() {
        CompoundTag tag = new CompoundTag();
        tag.put("ledger", new ListTag());
        return CrimeWorldData.load(tag, RegistryAccess.EMPTY);
    }

    /**
     * One instance, reused. A manual policy mints a random id, so building a fresh one per call would
     * silently compare two different policies and make a passing round-trip assertion meaningless.
     */
    private static final PropertyPolicy POLICY = PropertyPolicy.container(OVERWORLD, CHEST,
            new TownsteadBuildingRef(OVERWORLD, 3, 7, 12), PropertyOwnerKind.VILLAGE, null,
            PropertyAccessRule.RESIDENTS, false, PropertySource.MANUAL, "operator", 100L);

    private static PropertyPolicy policy() {
        return POLICY;
    }

    private static TransferAttribution attribution(int count, long time, int sequence) {
        TransferAttribution.CommittedTransfer transfer = new TransferAttribution.CommittedTransfer(
                ALICE, PropertyActor.Kind.PLAYER, OVERWORLD, CHEST, TransferAttribution.Direction.OUT,
                "Bread", "minecraft:bread#", count, time, sequence);
        return TransferAttribution.of(transfer, policy(),
                PropertyAccess.Decision.denied("only residents may take from this"), null, true);
    }

    @Test
    void oneCommittedTransferIsOneReceiptHoweverOftenItIsSeen() {
        CrimeWorldData data = store();
        TransferAttribution attribution = attribution(4, 500L, 0);
        UUID incident = UUID.randomUUID();

        assertTrue(data.putPropertyReceipt(PropertyReceipt.lost(attribution, policy(), incident)));
        assertTrue(data.hasPropertyReceipt(attribution.transferId()));
        // The replay: the same observation, judged again after a reconnect.
        assertTrue(data.putPropertyReceipt(
                PropertyReceipt.lost(attribution(4, 500L, 0), policy(), incident)));

        assertEquals(1, data.propertyReceipts().size(),
                "the derived transfer id is what prevents a duplicate debit");
    }

    @Test
    void twoTransfersInOneTickAreTwoReceipts() {
        CrimeWorldData data = store();

        data.putPropertyReceipt(PropertyReceipt.lost(attribution(16, 500L, 0), policy(), null));
        data.putPropertyReceipt(PropertyReceipt.lost(attribution(16, 500L, 1), policy(), null));

        assertEquals(2, data.propertyReceipts().size(),
                "a receipt records the actual quantity of one transfer; two stacks are two lots");
    }

    @Test
    void restitutionMarksAReceiptReturnedExactlyOnce() {
        PropertyReceipt lost = PropertyReceipt.lost(attribution(4, 500L, 0), policy(), UUID.randomUUID());

        PropertyReceipt restored = lost.restored(900L);
        PropertyReceipt again = restored.restored(1500L);

        assertEquals(PropertyReceipt.Outcome.RESTORED, restored.outcome());
        assertEquals(900L, restored.resolvedAt());
        assertSame(restored, again, "a second turn-in against the same lot must change nothing");
        assertFalse(restored.outstanding());
    }

    @Test
    void anUnresolvedReceiptStaysUnresolvedAndNeverOverwritesAReturn() {
        PropertyReceipt lost = PropertyReceipt.lost(attribution(4, 500L, 0), policy(), null);

        PropertyReceipt uncertain = lost.unresolved(700L);
        PropertyReceipt restored = lost.restored(700L);

        assertEquals(PropertyReceipt.Outcome.UNRESOLVED, uncertain.outcome());
        assertTrue(uncertain.outstanding(), "uncertainty is retained rather than settled");
        assertSame(restored, restored.unresolved(800L),
                "a settled loss must never be reopened by a reconciliation pass");
    }

    @Test
    void aReceiptSurvivesASaveAndLoadWithItsHistoryIntact() {
        CrimeWorldData data = store();
        UUID incident = UUID.randomUUID();
        PropertyReceipt receipt = PropertyReceipt.lost(attribution(4, 500L, 0), policy(), incident);
        assertTrue(data.putPropertyPolicy(policy()));
        assertTrue(data.putPropertyReceipt(receipt));

        CrimeWorldData reloaded = CrimeWorldData.load(
                data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        PropertyReceipt round = reloaded.propertyReceipt(receipt.transferId());
        assertNotNull(round);
        assertEquals(receipt, round);
        assertEquals(incident, round.incidentId());
        assertEquals(List.of(policy()), reloaded.propertyPolicies());
    }

    @Test
    void onlyOutstandingReceiptsForThatActorAtThatContainerAreOfferedForRestitution() {
        CrimeWorldData data = store();
        PropertyReceipt mine = PropertyReceipt.lost(attribution(4, 500L, 0), policy(), null);
        PropertyReceipt settled = PropertyReceipt.lost(attribution(4, 600L, 0), policy(), null)
                .restored(700L);
        data.putPropertyReceipt(mine);
        data.putPropertyReceipt(settled);

        List<PropertyReceipt> outstanding = data.outstandingPropertyReceipts(ALICE, OVERWORLD, CHEST);

        assertEquals(List.of(mine), outstanding);
        assertTrue(data.outstandingPropertyReceipts(UUID.randomUUID(), OVERWORLD, CHEST).isEmpty());
        assertTrue(data.outstandingPropertyReceipts(ALICE, OVERWORLD, new BlockPos(0, 0, 0)).isEmpty());
    }

    /**
     * A short return leaves the loss outstanding and says so.
     *
     * <p>The rule {@code PropertyTheftService.restore} implements, stated on the receipt itself:
     * {@code UNRESOLVED} is still outstanding, so handing back four of sixty-four loaves cannot earn
     * the restitution treatment for the other sixty.
     */
    @Test
    void anUncertainOutcomeIsStillAnOutstandingLoss() {
        PropertyReceipt lost = PropertyReceipt.lost(attribution(64, 500L, 0), policy(), null);

        PropertyReceipt partial = lost.unresolved(900L);

        assertTrue(partial.outstanding());
        assertEquals(PropertyReceipt.Outcome.UNRESOLVED, partial.outcome());
        assertEquals(64, partial.count(), "the quantity that was lost does not change because some "
                + "of it came back");
    }

    @Test
    void anUnreadableReceiptRowIsQuarantinedRatherThanDropped() {
        CompoundTag tag = new CompoundTag();
        tag.put("ledger", new ListTag());
        ListTag receipts = new ListTag();
        CompoundTag broken = new CompoundTag();
        broken.putString("outcome", "lost");
        receipts.add(broken);
        tag.put("propertyReceipts", receipts);

        CrimeWorldData data = CrimeWorldData.load(tag, RegistryAccess.EMPTY);

        assertTrue(data.propertyReceipts().isEmpty());
        assertEquals(1, data.quarantineCount(),
                "the only thing worse than not reading a loss record is losing it");
    }
}
