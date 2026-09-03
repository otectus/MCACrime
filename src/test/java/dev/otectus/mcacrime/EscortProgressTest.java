package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.EscortRestraint;
import dev.otectus.mcacrime.enforcement.EscortService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The physical half of the escort: how hard the lead pulls, and when the walk gives up.
 *
 * <p>Both are pure so the behaviour that decides whether an arrest feels like being held or like a
 * rubber band can be pinned without a world.
 */
class EscortProgressTest {

    // ---------------------------------------------------------------- the lead

    @Test
    void insideTheSoftRadiusThePlayerIsLeftAlone() {
        assertEquals(0.0, EscortRestraint.pullStrength(0.0, 25.0, 256.0));
        assertEquals(0.0, EscortRestraint.pullStrength(24.9, 25.0, 256.0));
        assertEquals(0.0, EscortRestraint.pullStrength(25.0, 25.0, 256.0),
                "the boundary itself is still free movement");
    }

    @Test
    void theLeadTightensRatherThanSnapping() {
        double near = EscortRestraint.pullStrength(50.0, 25.0, 256.0);
        double mid = EscortRestraint.pullStrength(150.0, 25.0, 256.0);
        double far = EscortRestraint.pullStrength(255.0, 25.0, 256.0);
        assertTrue(near > 0.0, "past the soft radius something must pull");
        assertTrue(mid > near, "and it must rise with distance");
        assertTrue(far > mid);
    }

    @Test
    void thePullIsCappedAndNeverNegative() {
        double atHard = EscortRestraint.pullStrength(256.0, 25.0, 256.0);
        double wayPast = EscortRestraint.pullStrength(10_000.0, 25.0, 256.0);
        assertEquals(atHard, wayPast, 1.0E-9, "beyond the tether the pull does not keep growing");
        for (double d : new double[] {0.0, 1.0, 25.0, 100.0, 256.0, 1.0E6}) {
            assertTrue(EscortRestraint.pullStrength(d, 25.0, 256.0) >= 0.0);
        }
    }

    /** A misconfigured pair of radii must still produce something sane rather than dividing by zero. */
    @Test
    void aLeashWiderThanTheTetherDegradesToAFullPull() {
        double strength = EscortRestraint.pullStrength(100.0, 256.0, 25.0);
        assertTrue(strength > 0.0 || strength == 0.0, "no NaN, no infinity");
        assertFalse(Double.isNaN(EscortRestraint.pullStrength(300.0, 256.0, 256.0)));
    }

    // ---------------------------------------------------------------- stuck detection

    @Test
    void gettingCloserResetsTheStrikeCount() {
        assertEquals(0, EscortService.nextStrikes(100.0, 50.0, 4));
        assertEquals(0, EscortService.nextStrikes(Double.MAX_VALUE, 900.0, 0), "the first scan is progress");
    }

    @Test
    void standingStillAccumulatesStrikes() {
        assertEquals(1, EscortService.nextStrikes(50.0, 50.0, 0));
        assertEquals(5, EscortService.nextStrikes(50.0, 50.0, 4));
        assertEquals(1, EscortService.nextStrikes(50.0, 80.0, 0), "moving further away is not progress");
    }

    /** Shuffling on the spot against a fence post must not read as progress forever. */
    @Test
    void aMarginalImprovementDoesNotCountAsProgress() {
        assertEquals(1, EscortService.nextStrikes(50.0, 49.5, 0));
        assertEquals(0, EscortService.nextStrikes(50.0, 48.0, 3));
    }

    @Test
    void theLimitDecidesWhenTheWalkGivesUp() {
        assertFalse(EscortService.isStuck(5, 6));
        assertTrue(EscortService.isStuck(6, 6));
        assertTrue(EscortService.isStuck(7, 6));
        assertFalse(EscortService.isStuck(1000, 0), "a limit of zero disables the check rather than firing it");
    }

    // ---------------------------------------------------------------- teleport detection

    @Test
    void aJumpLargerThanTheTetherReadsAsATeleportRatherThanASprint() {
        double tetherSqr = 16.0 * 16.0;
        assertFalse(EscortService.looksLikeTeleport(100.0, tetherSqr), "walking");
        assertFalse(EscortService.looksLikeTeleport(tetherSqr, tetherSqr), "exactly at the limit is not a jump");
        assertTrue(EscortService.looksLikeTeleport(10_000.0, tetherSqr), "an operator /tp across the village");
    }

    // ---------------------------------------------------------------- the step decision

    @Test
    void arrivalIsCheckedBeforeEverythingElse() {
        // Inside the cell but outside the tether and out of time: still ARRIVED. An escort that lands
        // on the last tick has arrived, not failed.
        assertEquals(EscortService.Step.ARRIVED,
                EscortService.decide(true, true, 10_000.0, 256.0, true, 999L, 1L));
    }

    @Test
    void breakingTheTetherIsAbandonedAndBeingStuckIsNot() {
        assertEquals(EscortService.Step.TETHER_BROKEN,
                EscortService.decide(true, false, 300.0, 256.0, false, 0L, 1000L),
                "running from a surrender is a decision, and gets a consequence");
        assertEquals(EscortService.Step.COMPLETE_BY_TELEPORT,
                EscortService.decide(true, false, 4.0, 256.0, true, 0L, 1000L),
                "a guard that cannot path must never be able to cancel a sentence");
    }

    @Test
    void noGuardOrNoTimeFinishesTheArrestRatherThanDroppingIt() {
        assertEquals(EscortService.Step.COMPLETE_BY_TELEPORT,
                EscortService.decide(false, false, 0.0, 256.0, false, 0L, 1000L));
        assertEquals(EscortService.Step.COMPLETE_BY_TELEPORT,
                EscortService.decide(true, false, 4.0, 256.0, false, 1000L, 1000L));
    }

    @Test
    void anOrdinaryScanKeepsWalking() {
        assertEquals(EscortService.Step.CONTINUE,
                EscortService.decide(true, false, 4.0, 256.0, false, 10L, 1000L));
    }

    /** The pre-0.4.0 radius-based overload keeps returning exactly what it always did. */
    @Test
    @SuppressWarnings("deprecation")
    void theOlderOverloadIsUnchanged() {
        assertEquals(EscortService.Step.ARRIVED,
                EscortService.decide(true, 4.0, 256.0, 4.0, 9.0, 0L, 1000L));
        assertEquals(EscortService.Step.TETHER_BROKEN,
                EscortService.decide(true, 300.0, 256.0, 400.0, 9.0, 0L, 1000L));
        assertEquals(EscortService.Step.COMPLETE_BY_TELEPORT,
                EscortService.decide(false, 0.0, 256.0, 400.0, 9.0, 0L, 1000L));
        assertEquals(EscortService.Step.CONTINUE,
                EscortService.decide(true, 4.0, 256.0, 400.0, 9.0, 0L, 1000L));
    }
}
