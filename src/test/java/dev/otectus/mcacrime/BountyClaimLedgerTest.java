package dev.otectus.mcacrime;

import dev.otectus.mcacrime.bounty.BountyClaimKey;
import dev.otectus.mcacrime.bounty.BountyClaimLedger;
import dev.otectus.mcacrime.bounty.BountyResolutionType;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec's bounty-exploit list, one test per line of it.
 *
 * <p>Every farm the spec names is a claim-key failure, so this is where they are closed. The tests run
 * against {@link CrimeWorldData} directly rather than a server, because the claim table is the thing
 * being asserted about and a running game adds nothing to the argument.
 */
class BountyClaimLedgerTest {

    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID HUNTER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static boolean claim(CrimeWorldData data, BountyClaimKey key, UUID claimant, long now) {
        return BountyClaimLedger.tryClaim(data, key, claimant, 12L, now, BountyResolutionType.KILLED);
    }

    @Test
    void oneWarrantedOutlawKilledOncePaysOnce() {
        CrimeWorldData data = new CrimeWorldData();
        BountyClaimKey key = new BountyClaimKey(TARGET, UUID.randomUUID(), 1L);
        assertTrue(claim(data, key, HUNTER, 100L));
        assertEquals(1, data.bountyClaims().size());
    }

    @Test
    void theSameWarrantRevisionResolvingTwicePaysOnce() {
        CrimeWorldData data = new CrimeWorldData();
        BountyClaimKey key = new BountyClaimKey(TARGET, UUID.randomUUID(), 1L);
        assertTrue(claim(data, key, HUNTER, 100L));
        assertFalse(claim(data, key, HUNTER, 200L), "the second resolution must not pay");
        assertFalse(claim(data, key, UUID.randomUUID(), 300L),
                "nor must a different hunter on the same revision");
    }

    @Test
    void respawningWithoutANewRevisionPaysNothing() {
        // Dying and coming back does not touch the warrant, so the key is byte-for-byte the same one.
        CrimeWorldData data = new CrimeWorldData();
        UUID warrant = UUID.randomUUID();
        assertTrue(claim(data, new BountyClaimKey(TARGET, warrant, 1L), HUNTER, 100L));
        assertFalse(claim(data, new BountyClaimKey(TARGET, warrant, 1L), HUNTER, 24_000L));
    }

    @Test
    void aNewOffenceBumpsTheRevisionAndIsPayableAgain() {
        CrimeWorldData data = new CrimeWorldData();
        UUID warrant = UUID.randomUUID();
        assertTrue(claim(data, new BountyClaimKey(TARGET, warrant, 1L), HUNTER, 100L));
        assertTrue(claim(data, new BountyClaimKey(TARGET, warrant, 2L), HUNTER, 200L),
                "a fresh offence is a fresh bounty, which is the point of the revision");
    }

    @Test
    void theTargetCannotClaimTheirOwnBounty() {
        CrimeWorldData data = new CrimeWorldData();
        assertFalse(claim(data, new BountyClaimKey(TARGET, UUID.randomUUID(), 1L), TARGET, 100L));
        assertEquals(0, data.bountyClaims().size(), "a refused self-claim must not even be recorded");
    }

    @Test
    void claimStateSurvivesASaveAndReload() {
        // "Disconnect/reconnect does not reset claim state" — the claim lives in world data, so the
        // proof is that it round-trips.
        CrimeWorldData data = new CrimeWorldData();
        BountyClaimKey key = new BountyClaimKey(TARGET, UUID.randomUUID(), 1L);
        assertTrue(claim(data, key, HUNTER, 100L));

        CrimeWorldData reloaded = CrimeWorldData.load(data.save(new CompoundTag()));
        assertFalse(claim(reloaded, key, HUNTER, 500L));
    }

    @Test
    void retentionForgetsAncientClaimsAndKeepsRecentOnes() {
        CrimeWorldData data = new CrimeWorldData();
        // claimedAt is a game time; day 1 and day 40.
        assertTrue(claim(data, new BountyClaimKey(TARGET, UUID.randomUUID(), 1L), HUNTER, 24_000L));
        assertTrue(claim(data, new BountyClaimKey(TARGET, UUID.randomUUID(), 1L), HUNTER, 40L * 24_000L));

        assertEquals(1, BountyClaimLedger.expire(data, 50L, 30));
        assertEquals(1, data.bountyClaims().size(), "the day-40 claim is still inside retention");
    }

    @Test
    void retentionIsANoOpEarlyInAWorldAndWhenDisabled() {
        CrimeWorldData data = new CrimeWorldData();
        assertTrue(claim(data, new BountyClaimKey(TARGET, UUID.randomUUID(), 1L), HUNTER, 0L));
        assertEquals(0, BountyClaimLedger.expire(data, 5L, 30), "day 5 is younger than the window");
        assertEquals(0, BountyClaimLedger.expire(data, 5000L, 0), "retention off forgets nothing");
        assertEquals(1, data.bountyClaims().size());
    }

    @Test
    void theKeyNamesAllThreePartsSoNoTwoWarrantsCollide() {
        UUID warrant = UUID.randomUUID();
        String first = new BountyClaimKey(TARGET, warrant, 1L).asKey();
        assertFalse(first.equals(new BountyClaimKey(TARGET, warrant, 2L).asKey()));
        assertFalse(first.equals(new BountyClaimKey(TARGET, UUID.randomUUID(), 1L).asKey()));
        assertFalse(first.equals(new BountyClaimKey(HUNTER, warrant, 1L).asKey()));
    }
}
