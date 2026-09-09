package dev.otectus.mcacrime;

import dev.otectus.mcacrime.bounty.*;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.economy.account.TransactionReceipt;
import dev.otectus.mcacrime.state.world.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BountyPaymentRecoveryTest {
    private static final UUID HUNTER = UUID.randomUUID();
    private static final BountyClaimKey KEY = new BountyClaimKey(UUID.randomUUID(), UUID.randomUUID(), 1);
    private static final String BANK = "test:bank";
    private static BountyService.Payout claim(CrimeWorldData data) {
        return BountyService.pay(data, KEY, HUNTER, 100, 1, BountyResolutionType.CAPTURED_ALIVE, BANK);
    }
    @Test void fullReceiptsRefuseTheClaimWithoutConsumingEntitlement() {
        CrimeWorldData data = new CrimeWorldData();
        for (int i = 0; i < 4096; i++) data.putTransaction(new TransactionReceipt(UUID.randomUUID(),
                TransactionReceipt.State.NEEDS_RECONCILIATION, BANK, 1, TransactionReason.RANSOM, null, HUNTER, 0, 0));
        assertFalse(claim(data).claimed());
        assertNull(data.bountyClaim(KEY.asKey()));
        assertNull(data.transaction(BountyPayments.id(KEY)));
    }
    @Test void claimAndUndeliveredPaymentSurviveRestartTogether() {
        CrimeWorldData data = new CrimeWorldData();
        assertTrue(claim(data).claimed());
        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY), net.minecraft.core.RegistryAccess.EMPTY);
        assertNotNull(loaded.bountyClaim(KEY.asKey()));
        assertEquals(TransactionReceipt.State.AWAITING_DELIVERY, loaded.transaction(BountyPayments.id(KEY)).state());
        assertFalse(claim(loaded).claimed());
        AtomicInteger credits = new AtomicInteger();
        assertTrue(BountyPayments.deliver(loaded, KEY, HUNTER, BANK, amount -> { credits.incrementAndGet(); return 0; }, 2));
        assertFalse(BountyPayments.deliver(loaded, KEY, HUNTER, BANK, amount -> { credits.incrementAndGet(); return 0; }, 3));
        assertEquals(1, credits.get());
    }
    @Test void partialInventoryDeliveryRetainsOnlyTheExactRemainder() {
        CrimeWorldData data = new CrimeWorldData(); claim(data);
        assertFalse(BountyPayments.deliver(data, KEY, HUNTER, BANK, amount -> { assertEquals(100, amount); return 37; }, 2));
        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY), net.minecraft.core.RegistryAccess.EMPTY);
        assertEquals(37, loaded.transaction(BountyPayments.id(KEY)).amount());
        assertTrue(BountyPayments.deliver(loaded, KEY, HUNTER, BANK, amount -> { assertEquals(37, amount); return 0; }, 3));
        assertEquals(100, loaded.bountyClaim(KEY.asKey()).reward());
    }
    @Test void noSpaceAndChangedProviderNeverDiscardThePayment() {
        CrimeWorldData data = new CrimeWorldData(); claim(data);
        assertFalse(BountyPayments.deliver(data, KEY, HUNTER, "test:other", amount -> { fail("wrong provider"); return 0; }, 2));
        assertFalse(BountyPayments.deliver(data, KEY, HUNTER, BANK, amount -> amount, 3));
        assertEquals(100, data.transaction(BountyPayments.id(KEY)).amount());
        assertEquals(TransactionReceipt.State.AWAITING_DELIVERY, data.transaction(BountyPayments.id(KEY)).state());
    }
    @Test void exceptionAfterPossibleCreditIsNeverAutomaticallyRetried() {
        CrimeWorldData data = new CrimeWorldData(); claim(data);
        assertFalse(BountyPayments.deliver(data, KEY, HUNTER, BANK, amount -> { throw new IllegalStateException("bank interrupted"); }, 2));
        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY), net.minecraft.core.RegistryAccess.EMPTY);
        assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, loaded.transaction(BountyPayments.id(KEY)).state());
        assertFalse(BountyPayments.deliver(loaded, KEY, HUNTER, BANK, amount -> { fail("uncertain retry"); return 0; }, 3));
        assertEquals(0, BountyClaimLedger.expire(loaded, 500, 30));
    }
    @Test void inFlightReentryCannotIssueAnotherCreditOrClaim() {
        CrimeWorldData data = new CrimeWorldData(); claim(data);
        assertTrue(BountyPayments.deliver(data, KEY, HUNTER, BANK, amount -> {
            assertFalse(claim(data).claimed());
            assertFalse(BountyPayments.deliver(data, KEY, HUNTER, BANK, n -> { fail("reentry"); return 0; }, 2));
            CrimeWorldData savedMidCredit = CrimeWorldData.load(data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY), net.minecraft.core.RegistryAccess.EMPTY);
            assertFalse(BountyPayments.deliver(savedMidCredit, KEY, HUNTER, BANK, n -> { fail("restart of pending credit"); return 0; }, 3));
            return 0;
        }, 2));
    }
    @Test void fullClaimTableReleasesTheUnusedReceiptReservation() {
        CrimeWorldData data = new CrimeWorldData();
        for (int i = 0; i < 8192; i++) assertTrue(BountyClaimLedger.tryClaim(data,
                new BountyClaimKey(UUID.randomUUID(), UUID.randomUUID(), 1), HUNTER, 1, 1, BountyResolutionType.KILLED));
        assertFalse(claim(data).claimed());
        assertNull(data.transaction(BountyPayments.id(KEY)));
    }
}
