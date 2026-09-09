package dev.otectus.mcacrime;

import dev.otectus.mcacrime.bounty.*;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.economy.account.*;
import dev.otectus.mcacrime.state.world.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.otectus.mcacrime.economy.account.ReconciliationService.Decision.*;
import static dev.otectus.mcacrime.economy.account.ReconciliationService.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final BountyClaimKey KEY = new BountyClaimKey(UUID.randomUUID(), UUID.randomUUID(), 1);
    private static CrimeWorldData ambiguousBounty() {
        var data = new CrimeWorldData();
        assertTrue(BountyPayments.reserve(data, KEY, OWNER, 100, 1, BountyResolutionType.KILLED, "test:bank"));
        BountyPayments.deliver(data, KEY, OWNER, "test:bank", amount -> -1, 2);
        return data;
    }
    private static ReconciliationService.Result decide(CrimeWorldData data, UUID id, ReconciliationService.Decision decision) {
        return ReconciliationService.resolve(data, id, ReconciliationService.revision(data, data.transaction(id)),
                decision, "operator-test", "Verified the external ledger", 10);
    }
    @Test void verifiedNonDeliveryAllowsExactlyOneNewBountyAttempt() {
        var data = ambiguousBounty(); var id = BountyPayments.id(KEY);
        UUID revision = ReconciliationService.revision(data, data.transaction(id));
        assertEquals(APPLIED, decide(data, id, RETRY));
        assertEquals(STALE, ReconciliationService.resolve(data, id, revision, RETRY, "operator", "replay", 10));
        var loaded = CrimeWorldData.load(data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY), net.minecraft.core.RegistryAccess.EMPTY);
        assertEquals(1, loaded.reconciliationDecisions().size());
        var audit = loaded.reconciliationDecisions().get(0);
        assertEquals("NEEDS_RECONCILIATION", audit.receiptBefore().getString("state"));
        assertEquals("operator-test", audit.operator());
        assertTrue(BountyPayments.deliver(loaded, KEY, OWNER, "test:bank", amount -> 0, 11));
        assertFalse(BountyPayments.deliver(loaded, KEY, OWNER, "test:bank", amount -> { fail("repaid"); return 0; }, 12));
    }
    @Test void externallySettledBountyDoesNotIssueCurrencyOrReplayClaims() {
        var data = ambiguousBounty(); var id = BountyPayments.id(KEY);
        assertEquals(APPLIED, decide(data, id, DELIVERED));
        assertEquals(TERMINAL, decide(data, id, DELIVERED));
        assertFalse(BountyPayments.deliver(data, KEY, OWNER, "test:bank", amount -> { fail("already compensated"); return 0; }, 11));
        assertFalse(BountyPayments.reserve(data, KEY, OWNER, 100, 11, BountyResolutionType.KILLED, "test:bank"));
    }
    @Test void aChangedReceiptOrNoteCannotSilentlyApplyAnOldDecision() {
        var data = ambiguousBounty(); var id = BountyPayments.id(KEY);
        var old = data.transaction(id); UUID revision = ReconciliationService.revision(data, old);
        data.putTransaction(old.withState(old.state(), 3));
        assertEquals(STALE, ReconciliationService.resolve(data, id, revision, DELIVERED, "op", "checked", 10));
        assertEquals(INVALID_NOTE, ReconciliationService.resolve(data, id, ReconciliationService.revision(data, data.transaction(id)),
                DELIVERED, "op", " ", 10));
        assertTrue(data.reconciliationDecisions().isEmpty());
    }
    @Test void propertyAcknowledgementRemovesOnlyTheConfirmedLotAndPreservesItsAuditPayload() {
        var data = new CrimeWorldData();
        var lot = PropertyLot.ofCurrency(UUID.randomUUID(), OWNER, 40, "test:bank", UUID.randomUUID(), 1);
        var other = PropertyLot.ofCurrency(UUID.randomUUID(), OWNER, 9, "test:bank", UUID.randomUUID(), 1);
        data.putPropertyLot(lot);
        PropertyEscrow.deliverPending(data, OWNER, value -> null, 2);
        data.putPropertyLot(other);
        var id = PropertyEscrow.deliveryId(lot);
        assertEquals(UNSUPPORTED, decide(data, id, CANCELLED));
        assertEquals(APPLIED, decide(data, id, DELIVERED));
        assertNull(data.propertyLot(lot.lotId())); assertEquals(other, data.propertyLot(other.lotId()));
        assertEquals(lot.save(), data.reconciliationDecisions().get(0).propertyBefore());
        assertEquals(0, PropertyEscrow.deliverPending(data, OWNER, value -> value, 11));
    }
    @Test void propertyRetryRetainsExactPayloadAndUsesNormalDelivery() {
        var data = new CrimeWorldData();
        var lot = PropertyLot.ofCurrency(UUID.randomUUID(), OWNER, 40, "test:bank", UUID.randomUUID(), 1);
        data.putPropertyLot(lot);
        PropertyEscrow.deliverPending(data, OWNER, value -> null, 2);
        assertEquals(APPLIED, decide(data, PropertyEscrow.deliveryId(lot), RETRY));
        assertEquals(lot, data.propertyLot(lot.lotId()));
        assertEquals(1, PropertyEscrow.deliverPending(data, OWNER, value -> value.remaining(null, 0), 11));
    }
    @Test void arbitraryTransfersCannotBeRetriedButCanBeClosedWithAnAudit() {
        var data = new CrimeWorldData(); var id = UUID.randomUUID();
        data.putTransaction(new TransactionReceipt(id, TransactionReceipt.State.NEEDS_RECONCILIATION,
                "test:bank", 10, TransactionReason.RANSOM, OWNER, UUID.randomUUID(), 0, 1));
        assertEquals(UNSUPPORTED, decide(data, id, RETRY));
        assertEquals(APPLIED, decide(data, id, CANCELLED));
        assertEquals(TransactionReceipt.State.REJECTED, data.transaction(id).state());
    }
    @Test void fullAuditStoreRejectsCorrectionBeforeMutatingTheReceipt() {
        var data = ambiguousBounty(); var id = BountyPayments.id(KEY); var before = data.transaction(id);
        for (int i = 0; i < 4096; i++) assertTrue(data.appendReconciliation(new ReconciliationDecision(
                UUID.randomUUID(), id, "op", "DELIVERED", "test", before.save(), null, 1)));
        assertEquals(AUDIT_FULL, decide(data, id, DELIVERED));
        assertEquals(before, data.transaction(id));
    }
    @Test void schemaNineMigratesWithoutInventingLegacyBountyPayments() {
        var data = new CrimeWorldData();
        BountyClaimLedger.tryClaim(data, KEY, OWNER, 100, 1, BountyResolutionType.KILLED);
        CompoundTag old = data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY); old.putInt("schema", 9); old.remove("reconciliationDecisions");
        var loaded = CrimeWorldData.load(old, net.minecraft.core.RegistryAccess.EMPTY);
        assertTrue(loaded.transactions().isEmpty()); assertTrue(loaded.reconciliationDecisions().isEmpty());
        assertNotNull(loaded.bountyClaim(KEY.asKey()));
        assertEquals(10, loaded.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY).getInt("schema"));
    }
    @Test void futureSchemaRejectsOperatorChangesAndKeepsOriginalData() {
        var data = ambiguousBounty(); CompoundTag future = data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY); future.putInt("schema", 11);
        var frozen = CrimeWorldData.load(future, net.minecraft.core.RegistryAccess.EMPTY);
        assertEquals(READ_ONLY, ReconciliationService.resolve(frozen, BountyPayments.id(KEY), UUID.randomUUID(),
                DELIVERED, "op", "verified", 1));
        assertEquals(future, frozen.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY));
    }
}
