package dev.otectus.mcacrime;

import dev.otectus.mcacrime.activity.CrimeActivityOperation;
import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.ai.ReactionControlPolicy;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three failures the activity registry exists to make impossible.
 *
 * <p>A late finisher clearing somebody else's claim, a weak claim taking a villager from a strong one,
 * and a claim that outlives the subsystem that took it. Each of those is a villager left standing in a
 * field, and each of them was reachable with a plain "who owns this" map — which is why the ownership
 * is a number rather than a flag.
 */
class ActivityGenerationTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private UUID villager;

    @BeforeEach
    void reset() {
        CrimeActivityRegistry.clearAll();
        villager = UUID.randomUUID();
    }

    @AfterEach
    void tidy() {
        CrimeActivityRegistry.clearAll();
    }

    private long claim(CrimeActivityView.Kind kind, String owner, long now) {
        return CrimeActivityRegistry.claim(villager, OVERWORLD, kind, owner, now, 40);
    }

    @Test
    void anOlderReleaseCannotClearANewerClaim() {
        long panic = claim(CrimeActivityView.Kind.REACTION, "reaction", 0L);
        long arrest = claim(CrimeActivityView.Kind.ARREST, "arrest", 1L);

        assertNotEquals(CrimeActivityRegistry.REFUSED, panic);
        assertNotEquals(CrimeActivityRegistry.REFUSED, arrest);
        assertTrue(arrest > panic, "generations are monotonic; that is the whole mechanism");

        assertFalse(CrimeActivityRegistry.release(villager, panic),
                "the reaction shutting down two ticks late must not release the arrest");
        assertEquals(CrimeActivityView.Kind.ARREST,
                CrimeActivityRegistry.activeFor(villager, 2L).orElseThrow().kind());

        assertTrue(CrimeActivityRegistry.release(villager, arrest), "its own owner still can");
        assertTrue(CrimeActivityRegistry.activeFor(villager, 2L).isEmpty());
    }

    @Test
    void anOlderRenewCannotExtendANewerClaim() {
        long panic = claim(CrimeActivityView.Kind.REACTION, "reaction", 0L);
        claim(CrimeActivityView.Kind.ARREST, "arrest", 1L);

        assertFalse(CrimeActivityRegistry.renew(villager, panic, 2L),
                "losing the renew is how a superseded controller learns it is no longer the owner");
    }

    @Test
    void aLowerAuthorityClaimCannotPreEmptAHigherOne() {
        long custody = claim(CrimeActivityView.Kind.CUSTODY, "custody", 0L);
        assertNotEquals(CrimeActivityRegistry.REFUSED, custody);

        assertEquals(CrimeActivityRegistry.REFUSED, claim(CrimeActivityView.Kind.REACTION, "reaction", 1L),
                "a prisoner does not stop being a prisoner because somebody frightened them");
        assertEquals(CrimeActivityRegistry.REFUSED, claim(CrimeActivityView.Kind.HOLD, "law_hold", 1L));
        assertEquals(CrimeActivityRegistry.REFUSED, claim(CrimeActivityView.Kind.PURSUIT, "pursuit", 1L));

        assertEquals(CrimeActivityView.Kind.CUSTODY,
                CrimeActivityRegistry.activeFor(villager, 1L).orElseThrow().kind());
    }

    @Test
    void aHigherAuthorityClaimTakesOverAndAnEqualOneMayToo() {
        long hold = claim(CrimeActivityView.Kind.HOLD, "law_hold", 0L);
        long escort = claim(CrimeActivityView.Kind.ESCORT, "law_hold", 1L);

        assertNotEquals(CrimeActivityRegistry.REFUSED, escort);
        assertTrue(escort > hold);

        long pursuit = claim(CrimeActivityView.Kind.PURSUIT, "pursuit", 2L);
        assertNotEquals(CrimeActivityRegistry.REFUSED, pursuit,
                "two enforcement tasks of equal weight are a scheduling decision, not a conflict");
    }

    @Test
    void theSameOwnerReClaimingTheSameKindRenewsRatherThanRetakes() {
        long first = claim(CrimeActivityView.Kind.ESCORT, "law_hold", 0L);
        long second = claim(CrimeActivityView.Kind.ESCORT, "law_hold", 10L);

        assertEquals(first, second,
                "a producer that re-stamps every scan must not invalidate the release it is holding");
        assertEquals(50L, CrimeActivityRegistry.activeFor(villager, 10L).orElseThrow().expiresAt());
    }

    @Test
    void anExpiredClaimStopsAnsweringAndIsSweptAway() {
        claim(CrimeActivityView.Kind.PURSUIT, "pursuit", 0L);

        assertTrue(CrimeActivityRegistry.isClaimed(villager, 39L));
        assertFalse(CrimeActivityRegistry.isClaimed(villager, 40L),
                "the lease is exclusive: at the deadline the claim is already gone");
        assertEquals(1, CrimeActivityRegistry.claimedCount(), "still in the map until somebody sweeps");

        CrimeActivityRegistry.sweep(40L);
        assertEquals(0, CrimeActivityRegistry.claimedCount());
        assertTrue(CrimeActivityRegistry.activeFor(villager).isEmpty(),
                "and the no-clock form agrees, because the sweep is what moves its clock");
    }

    @Test
    void aSweepDoesNotDropAClaimThatIsStillLive() {
        long escort = claim(CrimeActivityView.Kind.ESCORT, "law_hold", 0L);
        CrimeActivityRegistry.sweep(20L);

        assertEquals(1, CrimeActivityRegistry.claimedCount());
        assertEquals(escort, CrimeActivityRegistry.generationOf(villager, 20L));
    }

    @Test
    void touchExtendsALiveClaimWithoutOwningIt() {
        long escort = claim(CrimeActivityView.Kind.ESCORT, "law_hold", 0L);

        assertTrue(CrimeActivityRegistry.touch(villager, 30L));
        assertEquals(escort, CrimeActivityRegistry.generationOf(villager, 30L),
                "extending a lease is not a takeover");
        assertTrue(CrimeActivityRegistry.isClaimed(villager, 60L));

        CrimeActivityRegistry.sweep(100L);
        assertFalse(CrimeActivityRegistry.touch(villager, 100L),
                "and it cannot resurrect a claim that has already lapsed");
    }

    @Test
    void releaseOwnedMatchesOnIdentityRatherThanNumber() {
        claim(CrimeActivityView.Kind.THIEF_ACTION, "thief", 0L);

        assertFalse(CrimeActivityRegistry.releaseOwned(villager, CrimeActivityView.Kind.THIEF_ACTION, "someone"));
        assertFalse(CrimeActivityRegistry.releaseOwned(villager, CrimeActivityView.Kind.MUGGING, "thief"));
        assertTrue(CrimeActivityRegistry.releaseOwned(villager, CrimeActivityView.Kind.THIEF_ACTION, "thief"));
        assertEquals(0, CrimeActivityRegistry.claimedCount());
    }

    @Test
    void anUnclaimedVillagerIsPermittedEverything() {
        for (CrimeActivityOperation operation : CrimeActivityOperation.values()) {
            assertTrue(CrimeActivityRegistry.permits(villager, operation));
            assertTrue(CrimeActivityRegistry.permits(null, operation));
        }
        assertTrue(CrimeActivityRegistry.isEmpty());
    }

    @Test
    void aClaimedVillagerCarriesItsRowOfTheTable() {
        claim(CrimeActivityView.Kind.CUSTODY, "custody", 0L);
        CrimeActivityRegistry.sweep(0L);

        assertFalse(CrimeActivityRegistry.permits(villager, CrimeActivityOperation.WORK_START));
        assertFalse(CrimeActivityRegistry.permits(villager, CrimeActivityOperation.DISPLAY_TOOL));
        assertTrue(CrimeActivityRegistry.permits(villager, CrimeActivityOperation.REACTION_LOCK));
    }

    @Test
    void aPreEmptedControllerDoesNotRestoreOverTheClaimThatTookIt() {
        long panic = claim(CrimeActivityView.Kind.REACTION, "reaction", 0L);
        long arrest = claim(CrimeActivityView.Kind.ARREST, "arrest", 1L);

        assertFalse(ReactionControlPolicy.mayRestore(panic, arrest),
                "handing the villager back here would clear the walk target the arrest just set");
        assertTrue(ReactionControlPolicy.mayRestore(arrest, arrest), "its own owner still may");
        assertTrue(ReactionControlPolicy.mayRestore(panic, CrimeActivityRegistry.REFUSED),
                "and with nothing live there is nothing to trample");
        assertFalse(ReactionControlPolicy.mayRestore(CrimeActivityRegistry.REFUSED, arrest),
                "a controller that never won a claim owns nothing to give back");
    }
}
