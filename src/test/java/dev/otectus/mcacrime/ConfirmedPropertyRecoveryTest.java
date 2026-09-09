package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.economy.account.TransactionReceipt;
import dev.otectus.mcacrime.mug.npc.StolenGoodsLedger;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.PropertyEscrow;
import dev.otectus.mcacrime.state.world.PropertyLot;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedPropertyRecoveryTest {
    final UUID thief = UUID.randomUUID(), owner = UUID.randomUUID(), transaction = UUID.randomUUID();
    final CrimeWorldData data = new CrimeWorldData();
    StolenGoodsRecord stolen() { return new StolenGoodsRecord(transaction, thief, owner, null, 20, 5, "test:coins"); }
    PropertyLot lot() { return PropertyLot.ofCurrency(UUID.randomUUID(), owner, 20, "test:coins", transaction, 5); }
    static CompoundTag stack(int count) {
        var tag = new CompoundTag(); tag.putString("id", "minecraft:diamond"); tag.putByte("Count", (byte)count);
        var custom = new CompoundTag(); custom.putString("name", "Recovered heirloom"); tag.put("tag", custom); return tag;
    }
    TransactionReceipt receipt(PropertyLot lot) { return data.transaction(PropertyEscrow.deliveryId(lot)); }

    @Test void deathEscrowRetainsOwnerAmountAndProvider() {
        data.putStolenGoods(stolen()); assertEquals(1, StolenGoodsLedger.escrowAll(data, thief, 10));
        var lot = data.propertyEscrowFor(owner).get(0); assertEquals(20, lot.currency()); assertEquals("test:coins", lot.providerId());
        assertEquals(transaction, lot.sourceRecordId()); assertNull(data.stolenGoods(transaction));
    }
    @Test void repeatedDeathCannotMintASecondLot() {
        data.putStolenGoods(stolen()); assertEquals(1, StolenGoodsLedger.escrowAll(data, thief, 10));
        assertEquals(0, StolenGoodsLedger.escrowAll(data, thief, 11)); assertEquals(1, data.propertyEscrow().size());
    }
    @Test void offlineOwnerPropertySurvivesWorldReload() {
        data.putStolenGoods(stolen()); StolenGoodsLedger.escrowAll(data, thief, 10);
        var loaded = CrimeWorldData.load(data.save(new CompoundTag()));
        assertTrue(loaded.stolenGoodsByThief(thief).isEmpty()); assertEquals(20, loaded.propertyEscrowFor(owner).get(0).currency());
    }
    @Test void fullEscrowKeepsTheLastPropertyRecord() {
        for (int i = 0; i < 4096; i++) data.putPropertyLot(PropertyLot.ofCurrency(UUID.randomUUID(), owner, 1, "", null, 0));
        data.putStolenGoods(stolen()); assertEquals(0, StolenGoodsLedger.escrowAll(data, thief, 10)); assertEquals(stolen(), data.stolenGoods(transaction));
    }
    @Test void existingPartialLotCannotBeOverwrittenWithTheOriginalAmount() {
        UUID id = UUID.nameUUIDFromBytes(("lot:" + transaction).getBytes(StandardCharsets.UTF_8));
        var partial = PropertyLot.ofCurrency(id, owner, 20, "test:coins", transaction, 0).remaining(null, 7);
        data.putPropertyLot(partial); data.putStolenGoods(stolen());
        assertEquals(0, StolenGoodsLedger.escrowAll(data, thief, 10)); assertEquals(partial, data.propertyLot(id)); assertNotNull(data.stolenGoods(transaction));
    }
    @Test void futureStoreRefusesRecoveryAndDelivery() {
        var tag = new CompoundTag(); tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        var frozen = CrimeWorldData.load(tag); assertEquals(0, StolenGoodsLedger.escrowAll(frozen, thief, 10));
        assertEquals(0, PropertyEscrow.deliverPending(frozen, owner, lot -> fail("frozen delivery")));
    }
    @Test void itemProvenanceAndProviderAreDefensivelyCopied() {
        var original = stack(3); var record = new StolenGoodsRecord(transaction, thief, owner, original, 0, 5, "test:coins");
        original.putByte("Count", (byte)40); record.stackTag().putByte("Count", (byte)20);
        assertEquals(3, record.stackTag().getByte("Count")); assertEquals(record, StolenGoodsRecord.load(record.save()));
        data.putStolenGoods(record); StolenGoodsLedger.escrowAll(data, thief, 10);
        var owed = data.propertyEscrowFor(owner).get(0); owed.stackTag().putByte("Count", (byte)30);
        assertEquals(stack(3), owed.stackTag());
    }
    @Test void oldCurrencyRecordsRemainExplicitlyUnidentified() {
        var legacy = new StolenGoodsRecord(transaction, thief, owner, null, 8, 0);
        assertEquals("", StolenGoodsRecord.load(legacy.save()).providerId());
    }
    @Test void deliveryReceiptExistsBeforeTheExternalHandover() {
        var lot = lot(); data.putPropertyLot(lot);
        assertEquals(1, PropertyEscrow.deliverPending(data, owner, current -> {
            assertEquals(TransactionReceipt.State.DELIVERY_PENDING, receipt(current).state());
            return current.remaining(null, 0);
        }, 100));
        assertEquals(TransactionReceipt.State.DELIVERED, receipt(lot).state()); assertEquals(100, receipt(lot).stamp());
    }
    @Test void reentrantDeliveryCannotTransferTheSameLotTwice() {
        var lot = lot(); data.putPropertyLot(lot); AtomicInteger calls = new AtomicInteger();
        assertEquals(1, PropertyEscrow.deliverPending(data, owner, current -> {
            calls.incrementAndGet(); assertEquals(0, PropertyEscrow.deliverPending(data, owner, nested -> fail("reentry")));
            return current.remaining(null, 0);
        })); assertEquals(1, calls.get());
    }
    @Test void throwingDeliveryRetainsPropertyAndNeverAutomaticallyRetries() {
        var lot = lot(); data.putPropertyLot(lot); AtomicInteger calls = new AtomicInteger();
        assertEquals(0, PropertyEscrow.deliverPending(data, owner, current -> { calls.incrementAndGet(); throw new IllegalStateException("ambiguous credit"); }));
        assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, receipt(lot).state());
        assertEquals(lot, data.propertyLot(lot.lotId()));
        assertEquals(0, PropertyEscrow.deliverPending(data, owner, current -> fail("retry"))); assertEquals(1, calls.get());
    }
    @Test void ambiguousReceiptSurvivesReloadAndRetention() {
        var lot = lot(); data.putPropertyLot(lot); PropertyEscrow.deliverPending(data, owner, current -> null);
        var loaded = CrimeWorldData.load(data.save(new CompoundTag())); loaded.pruneTransactions(Long.MAX_VALUE / 2);
        assertEquals(0, PropertyEscrow.deliverPending(loaded, owner, current -> fail("ambiguous replay")));
        assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, loaded.transaction(PropertyEscrow.deliveryId(lot)).state());
    }
    @Test void nullResultIsNotProofThatPropertyArrived() {
        var lot = lot(); data.putPropertyLot(lot); assertEquals(0, PropertyEscrow.deliverPending(data, owner, current -> null));
        assertNotNull(data.propertyLot(lot.lotId())); assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, receipt(lot).state());
    }
    @Test void noRoomCanBeRetriedWithoutChangingTheLot() {
        var lot = lot(); data.putPropertyLot(lot); assertEquals(0, PropertyEscrow.deliverPending(data, owner, current -> current));
        assertEquals(TransactionReceipt.State.REJECTED, receipt(lot).state());
        assertEquals(1, PropertyEscrow.deliverPending(data, owner, current -> current.remaining(null, 0)));
    }
    @Test void partialCurrencyGetsANewReceiptOnlyForItsRemainder() {
        var lot = lot(); data.putPropertyLot(lot); assertEquals(0, PropertyEscrow.deliverPending(data, owner, current -> current.remaining(null, 7)));
        var remaining = data.propertyLot(lot.lotId()); assertEquals(7, remaining.currency());
        assertNotEquals(PropertyEscrow.deliveryId(lot), PropertyEscrow.deliveryId(remaining));
        assertEquals(1, PropertyEscrow.deliverPending(data, owner, current -> { assertEquals(7, current.currency()); return current.remaining(null, 0); }));
    }
    @Test void partialItemKeepsItsExactNbtAndRemainder() {
        var lot = new PropertyLot(UUID.randomUUID(), owner, stack(3), 0, "", transaction, PropertyLot.DeliveryState.PENDING, 0);
        data.putPropertyLot(lot); assertEquals(0, PropertyEscrow.deliverPending(data, owner, current -> current.remaining(stack(2), 0)));
        assertEquals(stack(2), data.propertyLot(lot.lotId()).stackTag());
        assertEquals(1, PropertyEscrow.deliverPending(data, owner, current -> current.remaining(null, 0)));
    }
    @Test void invalidRemainderCannotIncreaseCurrency() {
        var lot = lot(); data.putPropertyLot(lot);
        PropertyEscrow.deliverPending(data, owner, current -> current.remaining(null, 21));
        assertEquals(lot, data.propertyLot(lot.lotId())); assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, receipt(lot).state());
    }
    @Test void changedItemCannotReplaceTheSavedProperty() {
        var lot = new PropertyLot(UUID.randomUUID(), owner, stack(3), 0, "", transaction, PropertyLot.DeliveryState.PENDING, 0);
        data.putPropertyLot(lot); var changed = stack(2); changed.putString("id", "minecraft:stick");
        PropertyEscrow.deliverPending(data, owner, current -> current.remaining(changed, 0));
        assertEquals(stack(3), data.propertyLot(lot.lotId()).stackTag()); assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, receipt(lot).state());
    }
    @Test void callbackMutationIsNotOverwrittenByAStaleDeliveryResult() {
        var lot = lot(); data.putPropertyLot(lot); var replacement = lot.remaining(null, 9);
        PropertyEscrow.deliverPending(data, owner, current -> { data.putPropertyLot(replacement); return current.remaining(null, 0); });
        assertEquals(replacement, data.propertyLot(lot.lotId())); assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, receipt(lot).state());
    }
    @Test void fullReceiptTablePreventsAnyExternalDelivery() {
        for (int i = 0; i < 4096; i++) data.putTransaction(new TransactionReceipt(UUID.randomUUID(), TransactionReceipt.State.NEEDS_RECONCILIATION,
                "test:coins", 1, TransactionReason.RECOVERY, null, owner, 0, 0));
        var lot = lot(); data.putPropertyLot(lot);
        assertEquals(0, PropertyEscrow.deliverPending(data, owner, current -> fail("no receipt space"))); assertEquals(lot, data.propertyLot(lot.lotId()));
    }
    @Test void deliveryIdIsStableAcrossNbtRoundTrip() {
        var lot = new PropertyLot(UUID.randomUUID(), owner, stack(3), 0, "", transaction, PropertyLot.DeliveryState.PENDING, 0);
        assertEquals(PropertyEscrow.deliveryId(lot), PropertyEscrow.deliveryId(PropertyLot.load(lot.save())));
    }
    @Test void oneFailedLotDoesNotPreventLaterLotsFromArriving() {
        var failed = lot(); var succeeds = lot(); data.putPropertyLot(failed); data.putPropertyLot(succeeds);
        assertEquals(1, PropertyEscrow.deliverPending(data, owner, current -> {
            if (current.lotId().equals(failed.lotId())) throw new IllegalStateException("failed lot");
            return current.remaining(null, 0);
        })); assertNotNull(data.propertyLot(failed.lotId())); assertNull(data.propertyLot(succeeds.lotId()));
    }
}
