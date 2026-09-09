package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.economy.account.*;
import dev.otectus.mcacrime.mug.npc.StolenGoodsLedger;
import dev.otectus.mcacrime.mug.npc.TheftExecutor.TheftResult;
import dev.otectus.mcacrime.state.world.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReviewCapacityRegressionTest {
    private static final UUID THIEF = UUID.randomUUID(), OWNER = UUID.randomUUID();
    private static TransactionReceipt receipt(UUID id) {
        return new TransactionReceipt(id, TransactionReceipt.State.NEEDS_RECONCILIATION, "test:bank",
                10, TransactionReason.RANSOM, null, null, 0, 0);
    }

    @Test void fullReceiptTablePreventsAllProviderCalls() {
        CrimeWorldData data = new CrimeWorldData();
        for (int i = 0; i < 4096; i++) assertTrue(data.putTransaction(receipt(UUID.randomUUID())).stored());
        AtomicInteger calls = new AtomicInteger();
        PurseAccess bank = bank(calls);
        var rejected = EconomicTransactionService.transfer(data, UUID.randomUUID(), TransactionReason.RANSOM,
                "test:bank", bank, 10, false, OWNER, THIEF, 1);
        assertEquals(TransactionReceipt.State.REJECTED, rejected.state());
        assertEquals(0, calls.get());
        assertEquals(4096, data.transactions().size());
    }

    @Test void reservationProtectsLastSlotDuringReentrantProviderCall() {
        CrimeWorldData data = new CrimeWorldData();
        for (int i = 0; i < 4095; i++) data.putTransaction(receipt(UUID.randomUUID()));
        UUID id = UUID.randomUUID();
        AtomicInteger debits = new AtomicInteger();
        PurseAccess bank = new PurseAccess() {
            public long available() { return 100; }
            public long debit(long amount, TransactionReason reason) {
                debits.incrementAndGet();
                assertEquals(TransactionReceipt.State.PREPARED, data.transaction(id).state());
                assertFalse(data.putTransaction(receipt(UUID.randomUUID())).stored());
                var replay = EconomicTransactionService.transfer(data, id, reason, "test:bank", this,
                        10, false, OWNER, THIEF, 2);
                assertEquals(TransactionReceipt.State.PREPARED, replay.state());
                return amount;
            }
            public boolean credit(long amount, TransactionReason reason) { return false; }
        };
        var result = EconomicTransactionService.transfer(data, id, TransactionReason.RANSOM, "test:bank",
                bank, 10, false, OWNER, THIEF, 1);
        assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, result.state());
        assertEquals(result, data.transaction(id));
        assertEquals(1, debits.get());
    }

    @Test void debitExceptionRemainsRecordedAndCannotBeRetried() {
        CrimeWorldData data = new CrimeWorldData();
        UUID id = UUID.randomUUID();
        AtomicInteger debits = new AtomicInteger();
        PurseAccess bank = new PurseAccess() {
            public long available() { return 100; }
            public long debit(long amount, TransactionReason reason) {
                debits.incrementAndGet();
                throw new IllegalStateException("ambiguous provider debit");
            }
            public boolean credit(long amount, TransactionReason reason) { fail("no known debit"); return false; }
        };
        for (int i = 0; i < 2; i++) {
            var result = EconomicTransactionService.transfer(data, id, TransactionReason.RANSOM,
                    "test:bank", bank, 10, false, OWNER, THIEF, 1);
            assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, result.state());
        }
        assertEquals(1, debits.get());
    }

    @Test void aChangedReplayCannotReportAnUnrelatedPaymentAsDelivered() {
        CrimeWorldData data = new CrimeWorldData();
        UUID id = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var bank = bank(calls);
        assertTrue(EconomicTransactionService.transfer(data, id, TransactionReason.RANSOM,
                "test:bank", bank, 10, false, OWNER, THIEF, 1).delivered());
        int before = calls.get();
        assertFalse(EconomicTransactionService.transfer(data, id, TransactionReason.BOUNTY,
                "test:bank", bank, 10, false, OWNER, THIEF, 2).delivered());
        assertFalse(EconomicTransactionService.transfer(data, id, TransactionReason.RANSOM,
                "test:bank", bank, 11, false, OWNER, THIEF, 2).delivered());
        assertFalse(EconomicTransactionService.transfer(data, id, TransactionReason.RANSOM,
                "test:other", bank, 10, false, OWNER, THIEF, 2).delivered());
        assertFalse(EconomicTransactionService.transfer(data, id, TransactionReason.RANSOM,
                "test:bank", bank, 10, true, OWNER, THIEF, 2).delivered());
        assertEquals(before, calls.get());
    }

    @Test void fullStolenGoodsTablePreventsRemoval() {
        CrimeWorldData data = new CrimeWorldData();
        for (int i = 0; i < 4096; i++) data.putStolenGoods(goods(UUID.randomUUID(), OWNER));
        assertTrue(StolenGoodsLedger.commitTheft(data, net.minecraft.core.RegistryAccess.EMPTY, UUID.randomUUID(), THIEF, OWNER, "test:bank", 1,
                () -> { fail("inventory must remain untouched when provenance is full"); return null; }).isEmpty());
    }

    @Test void theftReservationProtectsLastSlotAndRejectsReentry() {
        CrimeWorldData data = new CrimeWorldData();
        for (int i = 0; i < 4095; i++) data.putStolenGoods(goods(UUID.randomUUID(), OWNER));
        UUID id = UUID.randomUUID();
        assertTrue(StolenGoodsLedger.commitTheft(data, net.minecraft.core.RegistryAccess.EMPTY, id, THIEF, OWNER, "test:bank", 1, () -> {
            assertFalse(data.putStolenGoods(goods(UUID.randomUUID(), OWNER)).stored());
            assertTrue(StolenGoodsLedger.commitTheft(data, net.minecraft.core.RegistryAccess.EMPTY, id, THIEF, OWNER, "test:bank", 1,
                    () -> { fail("reentrant removal"); return null; }).isEmpty());
            return new TheftResult(null, 12);
        }).isPresent());
        assertEquals(12, data.stolenGoods(id).currency());
        assertEquals("test:bank", data.stolenGoods(id).providerId());
        assertTrue(StolenGoodsLedger.commitTheft(data, net.minecraft.core.RegistryAccess.EMPTY, id, THIEF, OWNER, "test:bank", 2,
                () -> { fail("duplicate removal"); return null; }).isEmpty());
    }

    @Test void emptyTheftReleasesReservationForTheNextAttempt() {
        CrimeWorldData data = new CrimeWorldData();
        UUID id = UUID.randomUUID();
        assertTrue(StolenGoodsLedger.commitTheft(data, net.minecraft.core.RegistryAccess.EMPTY, id, THIEF, OWNER, "test:bank", 1,
                TheftResult::nothing).isPresent());
        assertTrue(data.reserveTheft(id));
        data.finishTheftReservation(id);
    }

    @Test void arrestEscrowMovesOnlyTheSelectedOwnersGoodsAndKeepsProvider() {
        CrimeWorldData data = new CrimeWorldData();
        UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        data.putStolenGoods(goods(id, OWNER));
        data.putStolenGoods(goods(other, UUID.randomUUID()));
        assertEquals(1, StolenGoodsLedger.escrowForOwner(data, THIEF, OWNER, 1));
        assertNull(data.stolenGoods(id));
        assertNotNull(data.stolenGoods(other));
        assertEquals(0, StolenGoodsLedger.escrowForOwner(data, THIEF, OWNER, 2));
        assertEquals("test:bank", data.propertyEscrowFor(OWNER).get(0).providerId());
    }

    private static StolenGoodsRecord goods(UUID id, UUID owner) {
        return new StolenGoodsRecord(id, THIEF, owner, null, 10, 0, "test:bank");
    }
    private static PurseAccess bank(AtomicInteger calls) {
        return new PurseAccess() {
            public long available() { calls.incrementAndGet(); return 100; }
            public long debit(long amount, TransactionReason reason) { calls.incrementAndGet(); return amount; }
            public boolean credit(long amount, TransactionReason reason) { calls.incrementAndGet(); return true; }
        };
    }
}
