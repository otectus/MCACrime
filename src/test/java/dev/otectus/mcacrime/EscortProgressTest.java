package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.EscortService;
import dev.otectus.mcacrime.tether.EscortTransport;
import dev.otectus.mcacrime.tether.TetherPhysics;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The physical half of the escort: how hard the lead pulls, and when the walk gives up.
 *
 * <p>Both are pure so the behaviour that decides whether an arrest feels like being held or like a
 * rubber band can be pinned without a world.
 *
 * <p>The lead itself moved in 0.7.5 M4.3. {@code enforcement/EscortRestraint} is gone and one engine
 * holds every subject, so the pull assertions below are made against {@code tether/TetherPhysics} and
 * {@code tether/EscortTransport} instead. The behaviour they assert is the same behaviour, with one
 * deliberate addition: validity is now decided <em>before</em> any correction, which is the defect
 * the source could not fix because it teleported first.
 */
class EscortProgressTest {

    // ---------------------------------------------------------------- the lead

    private static Vec3 at(double x) {
        return new Vec3(x, 64.0, 0.0);
    }

    @Test
    void insideThePullLengthTheSubjectIsLeftAlone() {
        assertEquals(Vec3.ZERO, TetherPhysics.correction(at(0.0), at(0.0), 5.0, 12.0));
        assertEquals(Vec3.ZERO, TetherPhysics.correction(at(0.0), at(4.9), 5.0, 12.0));
        assertEquals(Vec3.ZERO, TetherPhysics.correction(at(0.0), at(5.0), 5.0, 12.0),
                "the boundary itself is still free movement");
    }

    @Test
    void theLeadTightensRatherThanSnapping() {
        double near = TetherPhysics.correction(at(0.0), at(6.0), 5.0, 12.0).length();
        double mid = TetherPhysics.correction(at(0.0), at(9.0), 5.0, 12.0).length();
        double far = TetherPhysics.correction(at(0.0), at(11.9), 5.0, 12.0).length();
        assertTrue(near > 0.0, "past the pull length something must pull");
        assertTrue(mid > near, "and it must rise with distance");
        assertTrue(far > mid);
    }

    @Test
    void thePullIsCappedAndAlwaysFinite() {
        double atLimit = TetherPhysics.correction(at(0.0), at(12.0), 5.0, 12.0).length();
        double wayPast = TetherPhysics.correction(at(0.0), at(10_000.0), 5.0, 12.0).length();
        assertEquals(atLimit, wayPast, 1.0E-9, "beyond the overextension the pull does not keep growing");
        assertEquals(TetherPhysics.MAX_CORRECTION, atLimit, 1.0E-9);
        for (double d : new double[] {0.0, 1.0, 5.0, 12.0, 256.0, 1.0E6}) {
            assertTrue(TetherPhysics.finite(TetherPhysics.correction(at(0.0), at(d), 5.0, 12.0)));
        }
    }

    /** A misconfigured pair of lengths must still produce something sane rather than dividing by zero. */
    @Test
    void aPullLongerThanTheOverextensionDegradesToAFullPull() {
        Vec3 correction = TetherPhysics.correction(at(0.0), at(20.0), 12.0, 5.0);
        assertTrue(TetherPhysics.finite(correction), "no NaN, no infinity");
        assertTrue(correction.length() <= TetherPhysics.MAX_CORRECTION + 1.0E-9);
        assertFalse(TetherPhysics.overextended(11.0, 12.0, 5.0),
                "nothing below the pull length is ever damaging, however the lengths are ordered");
    }

    /** Non-finite coordinates answer "no correction" rather than writing a NaN into a position. */
    @Test
    void impossibleCoordinatesCorrectNothing() {
        assertEquals(Vec3.ZERO,
                TetherPhysics.correction(new Vec3(Double.NaN, 0.0, 0.0), at(50.0), 5.0, 12.0));
        assertEquals(Vec3.ZERO,
                TetherPhysics.correction(at(0.0), new Vec3(0.0, Double.POSITIVE_INFINITY, 0.0), 5.0, 12.0));
    }

    /**
     * The ordering fix. A subject past the break distance is broken free <em>before</em> anything
     * pulls them back, so the break condition is reachable at all -- the source evaluates it after
     * the teleport, which is why its own 3.5-block break can never fire.
     */
    @Test
    void validityIsDecidedBeforeAnyCorrection() {
        double breakAt = EscortTransport.breakDistance(12.0);
        assertTrue(EscortTransport.valid(true, true, true, breakAt - 0.1, breakAt));
        assertFalse(EscortTransport.valid(true, true, true, breakAt + 0.1, breakAt),
                "past the break distance the hold is over, whatever the pull would have done");
        assertFalse(EscortTransport.valid(true, false, true, 1.0, breakAt), "nobody is holding them");
        assertFalse(EscortTransport.valid(true, true, false, 1.0, breakAt), "another dimension");
        assertFalse(EscortTransport.valid(false, true, true, 1.0, breakAt), "a dead subject");
        assertFalse(EscortTransport.valid(true, true, true, Double.NaN, breakAt));
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
