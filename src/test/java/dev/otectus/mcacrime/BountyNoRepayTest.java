package dev.otectus.mcacrime;

import dev.otectus.mcacrime.bounty.BountyClaimKey;
import dev.otectus.mcacrime.bounty.BountyClaimLedger;
import dev.otectus.mcacrime.bounty.BountyResolutionType;
import dev.otectus.mcacrime.bounty.BountyService;
import dev.otectus.mcacrime.bounty.BountyService.Payout;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.state.world.BountyClaimRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One head, one price, however many revisions (0.6.0, T18, audit finding B09).
 *
 * <p>The claim key is {@code (target, warrant, revision)}, which stops the same wanted state being
 * paid twice — but a revision mints a new key, and before 0.6.0 that new key paid the <em>whole</em>
 * price again. A player with a friend willing to re-offend once between kills was therefore a
 * repeatable income. The fix is subtraction rather than a new key rule: a claim records what it paid,
 * and a later claim against the same warrant is worth the difference and nothing more.
 *
 * <p>The legacy case is the uncomfortable one and it is decided deliberately (spec §9.2). A 0.5.1
 * claim recorded no amount, so there is nothing to subtract; treating it as zero would re-open exactly
 * the double-pay this closes, so an absent amount counts as the whole of the current price and the
 * warrant pays nothing further until it goes terminal. That costs a hunter a re-claim on an existing
 * world. It does not cost a server its economy.
 */
class BountyNoRepayTest {

    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID HUNTER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID OTHER_HUNTER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID WARRANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static BountyClaimKey key(long revision) {
        return new BountyClaimKey(TARGET, WARRANT, revision);
    }

    private static Payout pay(CrimeWorldData data, long revision, UUID claimant, long asking) {
        return BountyService.pay(data, key(revision), claimant, asking, 24000L * revision,
                BountyResolutionType.KILLED);
    }

    // ------------------------------------------------------------------ the difference, not the price

    @Test
    void aRevisionAtTheSamePricePaysNothingMore() {
        CrimeWorldData data = new CrimeWorldData();

        Payout first = pay(data, 1L, HUNTER, 250L);
        assertTrue(first.claimed());
        assertEquals(250L, first.amount());

        Payout second = pay(data, 2L, OTHER_HUNTER, 250L);
        assertTrue(second.claimed(), "the new revision is still a claim; it is simply worth nothing");
        assertEquals(0L, second.amount(), "the head has already been paid for at this price");
    }

    @Test
    void aRevisionAtAHigherPricePaysOnlyTheDifference() {
        CrimeWorldData data = new CrimeWorldData();

        assertEquals(250L, pay(data, 1L, HUNTER, 250L).amount());
        assertEquals(90L, pay(data, 2L, OTHER_HUNTER, 340L).amount(),
                "the head got more expensive; the hunter is owed the increase");
        assertEquals(0L, pay(data, 3L, HUNTER, 340L).amount(),
                "and the increase is only paid once either");
    }

    @Test
    void aRevisionAtALowerPriceNeverPaysBackwards() {
        CrimeWorldData data = new CrimeWorldData();
        assertEquals(250L, pay(data, 1L, HUNTER, 250L).amount());
        assertEquals(0L, pay(data, 2L, OTHER_HUNTER, 100L).amount());
    }

    @Test
    void aLegacyClaimWithNoRecordedAmountCountsAsFullyConsumed() {
        CrimeWorldData data = new CrimeWorldData();
        // Exactly what 0.5.1 wrote: a paid claim at revision 1 with no paidAmount field.
        BountyClaimRecord legacy = new BountyClaimRecord(TARGET, WARRANT, 1L, HUNTER, 250L, 1000L,
                BountyResolutionType.CAPTURED_ALIVE);
        assertEquals(OptionalLong.empty(), legacy.paidAmount());
        assertTrue(data.putBountyClaimIfAbsent(key(1L).asKey(), legacy));

        assertEquals(0L, pay(data, 2L, OTHER_HUNTER, 400L).amount(),
                "an unrecorded amount is consumed conservatively at the current price");
    }

    @Test
    void aClaimAgainstADifferentWarrantIsUntouchedByThisOne() {
        CrimeWorldData data = new CrimeWorldData();
        assertEquals(250L, pay(data, 1L, HUNTER, 250L).amount());

        UUID secondWarrant = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
        Payout fresh = BountyService.pay(data, new BountyClaimKey(TARGET, secondWarrant, 1L), HUNTER,
                250L, 50000L, BountyResolutionType.KILLED);
        assertEquals(250L, fresh.amount(), "a new warrant is a new head, and it is worth its own price");
    }

    // ------------------------------------------------------------------ retention

    @Test
    void expiryLeavesAClaimWhoseWarrantIsStillOpen() {
        CrimeWorldData data = new CrimeWorldData();
        pay(data, 1L, HUNTER, 250L);
        data.putWarrant(new Warrant(WARRANT, TARGET, 1L, 0L, 0L,
                ResourceLocation.fromNamespaceAndPath("mcacrime", "assault"), List.of(), true, 0L));

        assertEquals(0, BountyClaimLedger.expire(data, 500L, 30),
                "forgetting what has been paid while the warrant stands is how the next revision pays twice");
        assertNotNull(data.bountyClaim(key(1L).asKey()));
    }

    @Test
    void expiryForgetsAClaimWhoseWarrantIsGone() {
        CrimeWorldData data = new CrimeWorldData();
        pay(data, 1L, HUNTER, 250L);

        assertEquals(1, BountyClaimLedger.expire(data, 500L, 30));
        assertEquals(0L, BountyClaimLedger.alreadyPaid(data, TARGET, WARRANT, 250L));
    }
}
