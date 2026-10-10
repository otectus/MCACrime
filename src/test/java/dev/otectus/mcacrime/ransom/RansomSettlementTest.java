package dev.otectus.mcacrime.ransom;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a ransom payment has to clear before any money moves, and the order a paid one settles in
 * (0.7.7).
 *
 * <p>The guarantees are the user-facing ones: a failed or held payment frees nobody, a repeated
 * payment finds nobody left to free, and once the money has moved the captive goes free whatever
 * happens to the audit record.
 */
class RansomSettlementTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final long AMOUNT = 24L;

    private final UUID victim = UUID.randomUUID();
    private final UUID captor = UUID.randomUUID();
    private final RansomState demand = new RansomState(UUID.randomUUID(), victim, captor, UUID.randomUUID(),
            PayerTier.SPOUSE, AMOUNT, 0L, 12000L);

    private CustodyRecord kidnappedBy(UUID owner) {
        return new CustodyRecord(victim, false, false, CustodyOwner.kidnapper(owner), 0L,
                new BlockPos(0, 64, 0), OVERWORLD);
    }

    private RansomService.PaymentCheck check(CustodyRecord custody, boolean alive, boolean captorOnline,
                                             long balance) {
        return RansomService.checkPayment(demand, custody, alive, captorOnline, balance);
    }

    @Test
    void aPaymentThatClearsEveryCheckIsPayable() {
        assertEquals(RansomService.PaymentCheck.PAYABLE, check(kidnappedBy(captor), true, true, AMOUNT));
    }

    @Test
    void aPaymentOneCoinShortIsHeldAndFreesNobody() {
        assertEquals(RansomService.PaymentCheck.INSUFFICIENT, check(kidnappedBy(captor), true, true, AMOUNT - 1));
    }

    @Test
    void anAbsentCaptorHoldsTheDemand() {
        assertEquals(RansomService.PaymentCheck.CAPTOR_AWAY, check(kidnappedBy(captor), true, false, AMOUNT));
    }

    @Test
    void aDeadVictimFailsTheDemand() {
        assertEquals(RansomService.PaymentCheck.VICTIM_GONE, check(kidnappedBy(captor), false, true, AMOUNT));
    }

    @Test
    void aVictimTheLawNowHoldsFailsTheDemand() {
        CustodyRecord arrested = new CustodyRecord(victim, false, true, CustodyOwner.guard(UUID.randomUUID()),
                0L, new BlockPos(0, 64, 0), OVERWORLD);
        assertEquals(RansomService.PaymentCheck.JAILED, check(arrested, true, true, AMOUNT));
    }

    @Test
    void somebodyElseHoldingTheVictimIsNotPaidForByThisDemand() {
        assertEquals(RansomService.PaymentCheck.CAPTOR_CHANGED,
                check(kidnappedBy(UUID.randomUUID()), true, true, AMOUNT));
    }

    @Test
    void aRepeatedPaymentFindsNobodyLeftToFree() {
        // The first payment released the custody; a second one, however it arrives, has nothing to buy.
        assertEquals(RansomService.PaymentCheck.RESCUED, check(null, true, true, AMOUNT * 10));
    }

    @Test
    void custodyIsAskedBeforeMoneySoAFailedDemandNeverCharges() {
        assertEquals(RansomService.PaymentCheck.RESCUED, check(null, false, false, 0L));
        CustodyRecord arrested = new CustodyRecord(victim, false, true, CustodyOwner.guard(UUID.randomUUID()),
                0L, new BlockPos(0, 64, 0), OVERWORLD);
        assertEquals(RansomService.PaymentCheck.JAILED, check(arrested, false, false, 0L));
    }

    @Test
    void aDemandWithNoRecordedCaptorPaysNobody() {
        RansomState corrupt = RansomState.load(new net.minecraft.nbt.CompoundTag());
        assertEquals(RansomService.PaymentCheck.CAPTOR_CHANGED,
                RansomService.checkPayment(corrupt, kidnappedBy(captor), true, true, AMOUNT));
    }

    @Test
    void theCaptiveIsReleasedBeforeTheAuditIsWritten() {
        List<String> order = new ArrayList<>();
        RansomService.settleInOrder(() -> order.add("release"), () -> order.add("audit"), UUID.randomUUID());
        assertEquals(List.of("release", "audit"), order);
    }

    @Test
    void aFailingReleaseStillLeavesTheAuditOfAPaymentThatHappened() {
        List<String> order = new ArrayList<>();
        RansomService.settleInOrder(() -> {
            order.add("release");
            throw new IllegalStateException("a listener failed");
        }, () -> order.add("audit"), UUID.randomUUID());
        assertEquals(List.of("release", "audit"), order,
                "the money moved, so the record of it is written whatever the release did");
    }
}
