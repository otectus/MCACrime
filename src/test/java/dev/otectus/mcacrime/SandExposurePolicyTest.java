package dev.otectus.mcacrime;

import dev.otectus.mcacrime.effect.SandExposurePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who one sand burst blinds, and — more often — who it deliberately does not (0.7.2 §13.3, §13.7). */
class SandExposurePolicyTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    private static SandExposurePolicy.Candidate villager(UUID id, double distance) {
        return new SandExposurePolicy.Candidate(id, distance, false, false, false, false, false, false,
                false, false, false, false);
    }

    private static SandExposurePolicy.Candidate direct(UUID id) {
        return new SandExposurePolicy.Candidate(id, 0.0D, true, false, false, false, false, false,
                false, false, false, false);
    }

    private static SandExposurePolicy.Settings settings() {
        return SandExposurePolicy.Settings.defaults();
    }

    @Test
    void aDirectlyStruckTargetGetsOneDirectApplicationRatherThanDirectPlusSplash() {
        List<SandExposurePolicy.Application> applied =
                SandExposurePolicy.select(List.of(direct(A), villager(A, 0.1D)), settings());
        assertEquals(1, applied.size(), "the same victim must not be blinded twice by one bottle");
        assertTrue(applied.get(0).direct());
        assertEquals(SandExposurePolicy.DEFAULT_DIRECT_DURATION_TICKS, applied.get(0).durationTicks());
    }

    @Test
    void splashFallsOffWithDistanceAndStopsAtTheMinimumApplication() {
        var s = settings();
        assertEquals(SandExposurePolicy.DEFAULT_SPLASH_DURATION_TICKS,
                SandExposurePolicy.falloff(0.0D, s));
        assertEquals(SandExposurePolicy.DEFAULT_SPLASH_DURATION_TICKS / 2,
                SandExposurePolicy.falloff(s.radius() / 2, s));
        // At the rim the computed duration is zero, which is below the minimum, so nothing is applied
        // at all rather than a one-tick icon nobody can react to.
        assertTrue(SandExposurePolicy.select(List.of(villager(A, s.radius())), s).isEmpty());
        assertTrue(SandExposurePolicy.select(List.of(villager(A, s.radius() * 0.9D)), s).isEmpty(),
                "a duration under the minimum application is dropped, not rounded up");
    }

    @Test
    void nothingOutsideTheRadiusIsEverSelected() {
        assertTrue(SandExposurePolicy.select(
                List.of(villager(A, settings().radius() + 0.01D)), settings()).isEmpty());
    }

    @Test
    void aWallStopsSplashButNotADirectHit() {
        var occludedSplash = new SandExposurePolicy.Candidate(A, 0.5D, false, false, false, false, false,
                false, true, false, false, false);
        assertTrue(SandExposurePolicy.select(List.of(occludedSplash), settings()).isEmpty(),
                "sand does not blind through a wall");
        var occludedDirect = new SandExposurePolicy.Candidate(A, 0.0D, true, false, false, false, false,
                false, true, false, false, false);
        assertEquals(1, SandExposurePolicy.select(List.of(occludedDirect), settings()).size(),
                "a target the bottle physically hit was, by definition, not behind a wall");
    }

    @Test
    void candidateCollectionIsBoundedAndTheBoundIsAppliedBeforeSelection() {
        List<SandExposurePolicy.Candidate> crowd = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            crowd.add(villager(new UUID(0L, i), 0.1D));
        }
        var small = new SandExposurePolicy.Settings(2.0D, 80, 40, 10, 64, 100, false, true);
        assertEquals(100, SandExposurePolicy.bound(crowd, small.maxCandidates()).size());
        // 100 examined, 64 affected: both ceilings hold at once.
        assertEquals(64, SandExposurePolicy.select(crowd, small).size());
    }

    @Test
    void theAffectedCapIsNeverExceededEvenWhenEveryCandidateIsEligible() {
        List<SandExposurePolicy.Candidate> crowd = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            crowd.add(villager(new UUID(1L, i), 0.1D));
        }
        assertEquals(SandExposurePolicy.MAX_AFFECTED,
                SandExposurePolicy.select(crowd, settings()).size());
    }

    @Test
    void selectionIsNearestFirstAndTiesBreakDeterministicallyOnIdentity() {
        var small = new SandExposurePolicy.Settings(2.0D, 80, 40, 10, 2, 256, false, true);
        // C is nearest and must win; A and B are an exact tie and the lower UUID must win it, in both
        // input orders, or a crowd would blind different people on two identical servers.
        var forwards = SandExposurePolicy.select(
                List.of(villager(A, 0.5D), villager(B, 0.5D), villager(C, 0.1D)), small);
        var backwards = SandExposurePolicy.select(
                List.of(villager(B, 0.5D), villager(A, 0.5D), villager(C, 0.1D)), small);
        assertEquals(List.of(C, A), forwards.stream().map(SandExposurePolicy.Application::target).toList());
        assertEquals(forwards, backwards);
    }

    @Test
    void immunityProtectionAndSpectatorStatusAllRefuseTheEffect() {
        assertTrue(SandExposurePolicy.select(List.of(new SandExposurePolicy.Candidate(
                A, 0.1D, true, false, false, true, false, false, false, false, false, false)),
                settings()).isEmpty(), "an entity-type-immune target is immune even to a direct hit");
        assertTrue(SandExposurePolicy.select(List.of(new SandExposurePolicy.Candidate(
                A, 0.1D, true, false, false, false, true, false, false, false, false, false)),
                settings()).isEmpty(), "a configured protected entity is not blinded");
        assertTrue(SandExposurePolicy.select(List.of(new SandExposurePolicy.Candidate(
                A, 0.1D, true, true, false, false, false, true, false, false, false, false)),
                settings()).isEmpty(), "a creative or spectating player is not blinded");
    }

    @Test
    void activeSandAndRecoveryBothRefuseASecondBottle() {
        assertTrue(SandExposurePolicy.select(List.of(new SandExposurePolicy.Candidate(
                A, 0.0D, true, false, false, false, false, false, false, false, true, false)),
                settings()).isEmpty(), "a second bottle must not extend an active effect");
        assertTrue(SandExposurePolicy.select(List.of(new SandExposurePolicy.Candidate(
                A, 0.0D, true, false, false, false, false, false, false, false, false, true)),
                settings()).isEmpty(), "the recovery window refuses every thrower alike");
    }

    @Test
    void playersAreExcludedUnlessTheServerAndTheConfigBothAllowIt() {
        var player = new SandExposurePolicy.Candidate(B, 0.1D, true, true, false, false, false, false,
                false, false, false, false);
        var off = new SandExposurePolicy.Settings(2.0D, 80, 40, 10, 64, 256, false, true);
        var on = new SandExposurePolicy.Settings(2.0D, 80, 40, 10, 64, 256, true, true);
        var serverForbids = new SandExposurePolicy.Settings(2.0D, 80, 40, 10, 64, 256, true, false);
        assertTrue(SandExposurePolicy.select(List.of(player), off).isEmpty());
        assertEquals(1, SandExposurePolicy.select(List.of(player), on).size());
        assertTrue(SandExposurePolicy.select(List.of(player), serverForbids).isEmpty(),
                "a server that forbids PvP is not overruled by a mod toggle");
    }

    @Test
    void teamFriendlyFireIsRespectedEvenWithPlayerEffectsEnabled() {
        var teammate = new SandExposurePolicy.Candidate(B, 0.1D, true, true, false, false, false, false,
                false, true, false, false);
        var on = new SandExposurePolicy.Settings(2.0D, 80, 40, 10, 64, 256, true, true);
        assertTrue(SandExposurePolicy.select(List.of(teammate), on).isEmpty());
    }

    @Test
    void theThrowerCanCatchTheirOwnSplashOnEveryServerAndItIsNeverPvp() {
        var self = new SandExposurePolicy.Candidate(A, 0.5D, false, true, true, false, false, false,
                false, true, false, false);
        var pvpForbidden = new SandExposurePolicy.Settings(2.0D, 80, 40, 10, 64, 256, false, false);
        List<SandExposurePolicy.Application> applied = SandExposurePolicy.select(List.of(self), pvpForbidden);
        assertEquals(1, applied.size(), "self-exposure is a deliberate risk, not PvP");
        assertTrue(applied.get(0).selfExposure());
        assertFalse(applied.get(0).hostile(), "and it can never become a crime against yourself");
    }

    @Test
    void closeContactIsTheOnlySightABlindedObserverKeeps() {
        assertTrue(SandExposurePolicy.withinCloseContact(SandExposurePolicy.CLOSE_CONTACT_RANGE));
        assertFalse(SandExposurePolicy.withinCloseContact(SandExposurePolicy.CLOSE_CONTACT_RANGE + 0.01D));
    }
}
