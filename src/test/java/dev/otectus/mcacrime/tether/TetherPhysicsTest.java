package dev.otectus.mcacrime.tether;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finite velocity, rejected cycles, bounded extreme coordinates (0.7.5 M4.1).
 *
 * <p>Three upstream failure modes in one place. The source replaces the subject's velocity outright
 * with an expression that is unbounded in the distance and applies it on the client as well with no
 * server agreement ({@code mixin/LivingEntityMixin.java:92-154}); it has no cycle test at all, so two
 * subjects chained to each other converge on a point and stay there; and a non-finite coordinate
 * propagates straight into an entity position.
 */
class TetherPhysicsTest {

    private static Vec3 at(double x, double z) {
        return new Vec3(x, 64.0D, z);
    }

    // ---------------------------------------------------------------- finite and bounded

    @Test
    void theCorrectionIsNeverLargerThanTheCap() {
        for (double d : new double[] {5.1D, 6.0D, 12.0D, 100.0D, 10_000.0D, 1.0E9D}) {
            Vec3 correction = TetherPhysics.correction(at(0.0D, 0.0D), at(d, 0.0D), 5.0D, 12.0D);
            assertTrue(TetherPhysics.finite(correction), "a correction must always be a real number");
            assertTrue(correction.length() <= TetherPhysics.MAX_CORRECTION + 1.0E-9D,
                    "at " + d + " blocks the pull was " + correction.length());
        }
    }

    @Test
    void theCorrectionIsHorizontalOnly() {
        Vec3 correction = TetherPhysics.correction(at(0.0D, 0.0D), new Vec3(20.0D, 400.0D, 0.0D),
                5.0D, 12.0D);
        assertEquals(0.0D, correction.y, 1.0E-12D,
                "a lead does not lift a prisoner out of a hole or press them into the floor");
    }

    @Test
    void aSubjectDirectlyBelowTheAnchorIsNotShoved() {
        Vec3 correction = TetherPhysics.correction(at(0.0D, 0.0D), new Vec3(0.0D, 400.0D, 0.0D),
                5.0D, 12.0D);
        assertEquals(Vec3.ZERO, correction, "there is no horizontal direction to pull in");
    }

    @Test
    void nonFiniteCoordinatesProduceNoCorrectionAtAll() {
        Vec3 nan = new Vec3(Double.NaN, 0.0D, 0.0D);
        Vec3 infinite = new Vec3(Double.POSITIVE_INFINITY, 0.0D, 0.0D);
        assertEquals(Vec3.ZERO, TetherPhysics.correction(nan, at(50.0D, 0.0D), 5.0D, 12.0D));
        assertEquals(Vec3.ZERO, TetherPhysics.correction(at(0.0D, 0.0D), infinite, 5.0D, 12.0D));
        assertFalse(TetherPhysics.finite(nan));
        assertFalse(TetherPhysics.finite(null));
    }

    @Test
    void extremeCoordinatesAnswerNoDistanceRatherThanAWrongOne() {
        double beyond = TetherPhysics.MAX_SANE_DISTANCE * 10.0D;
        assertTrue(Double.isNaN(TetherPhysics.distance(at(0.0D, 0.0D), at(beyond, 0.0D))),
                "an impossible separation is not a number, and not zero either");
        assertFalse(TetherPhysics.taut(Double.NaN, 5.0D));
        assertFalse(TetherPhysics.overextended(Double.NaN, 5.0D, 12.0D));
        assertEquals(Vec3.ZERO, TetherPhysics.correction(at(0.0D, 0.0D), at(beyond, 0.0D), 5.0D, 12.0D));
    }

    // ---------------------------------------------------------------- the two thresholds

    @Test
    void tautComesBeforeOverextendedAndNeverTheOtherWayRound() {
        assertFalse(TetherPhysics.taut(5.0D, 5.0D), "the boundary itself is still slack");
        assertTrue(TetherPhysics.taut(5.01D, 5.0D));
        assertFalse(TetherPhysics.overextended(6.0D, 5.0D, 12.0D), "taut is not injured");
        assertTrue(TetherPhysics.overextended(12.01D, 5.0D, 12.0D));
    }

    @Test
    void aMisorderedPairOfLengthsStillLeavesABandBeforeDamage() {
        // overextension shorter than the pull length: ConfigValidator refuses it, and this refuses to
        // act on it, so the first taut tick is never also the first damaging one.
        assertFalse(TetherPhysics.overextended(11.0D, 12.0D, 5.0D));
        assertTrue(TetherPhysics.overextended(12.5D, 12.0D, 5.0D));
    }

    @Test
    void suspensionDamageIsZeroUntilOverextendedAndBoundedAfterwards() {
        assertEquals(0.0F, TetherPhysics.suspensionDamage(6.0D, 5.0D, 12.0D, 2.0D, false));
        assertEquals(2.0F, TetherPhysics.suspensionDamage(13.0D, 5.0D, 12.0D, 2.0D, false));
        assertEquals(20.0F, TetherPhysics.suspensionDamage(13.0D, 5.0D, 12.0D, 1000.0D, false),
                "a hostile config cannot make one tick deal a thousand");
        assertEquals(0.0F, TetherPhysics.suspensionDamage(13.0D, 5.0D, 12.0D, Double.NaN, false));
        assertEquals(0.0F, TetherPhysics.suspensionDamage(13.0D, 5.0D, 12.0D, -5.0D, false));
    }

    @Test
    void aHarmlessTransportPolicyNeverInjuresThePrisoner() {
        assertEquals(0.0F, TetherPhysics.suspensionDamage(1000.0D, 5.0D, 12.0D, 2.0D, true),
                "a guard walking somebody to a cell is not trying to kill them");
        assertTrue(TetherDamage.harmless(TetherKind.ESCORT, true));
        assertFalse(TetherDamage.harmless(TetherKind.ESCORT, false));
        assertFalse(TetherDamage.harmless(TetherKind.CHAIN, true),
                "a kidnapper's chain has no custody transport policy behind it");
        assertFalse(TetherDamage.harmless(TetherKind.ANCHOR, true));
        assertFalse(TetherDamage.harmless(null, true));
    }

    // ---------------------------------------------------------------- cycles

    private static java.util.function.Function<UUID, UUID> holders(Map<UUID, UUID> map) {
        return map::get;
    }

    @Test
    void nobodyLeadsThemselves() {
        UUID a = UUID.randomUUID();
        assertTrue(TetherPhysics.formsCycle(a, a, holders(new HashMap<>())));
    }

    @Test
    void aTwoWayChainIsRejected() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, UUID> held = new HashMap<>();
        held.put(b, a); // b is already held by a
        assertTrue(TetherPhysics.formsCycle(a, b, holders(held)),
                "tying a to b would close the loop");
        assertFalse(TetherPhysics.formsCycle(b, a, holders(new HashMap<>())),
                "the same pair the other way round, with nothing existing, is fine");
    }

    @Test
    void aLongerLoopIsRejectedToo() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        Map<UUID, UUID> held = new HashMap<>();
        held.put(c, b);
        held.put(b, a);
        assertTrue(TetherPhysics.formsCycle(a, c, holders(held)));
    }

    @Test
    void anExistingLoopDoesNotMakeTheCheckRunForever() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        Map<UUID, UUID> held = new HashMap<>();
        held.put(a, b);
        held.put(b, a); // already corrupt
        assertTrue(TetherPhysics.formsCycle(outsider, a, holders(held)),
                "a chain that already loops is refused rather than walked");
    }

    @Test
    void anOrdinaryChainOfHoldersIsAccepted() {
        UUID guard = UUID.randomUUID();
        UUID prisoner = UUID.randomUUID();
        assertFalse(TetherPhysics.formsCycle(prisoner, guard, holders(new HashMap<>())));
        assertFalse(TetherPhysics.formsCycle(null, guard, holders(new HashMap<>())));
        assertFalse(TetherPhysics.formsCycle(prisoner, null, holders(new HashMap<>())));
    }

    // ---------------------------------------------------------------- lengths

    @Test
    void aLengthIsClampedIntoTheRangeATetherMayHave() {
        assertEquals(5.0D, TetherPhysics.boundedLength(5.0D, 1.0D));
        assertEquals(0.5D, TetherPhysics.boundedLength(0.01D, 1.0D), "never shorter than half a block");
        assertEquals(TetherRecord.MAX_LENGTH_BLOCKS, TetherPhysics.boundedLength(1.0E9D, 1.0D),
                "a hostile value cannot make a tether unbreakable");
        assertEquals(1.0D, TetherPhysics.boundedLength(Double.NaN, 1.0D));
        assertEquals(1.0D, TetherPhysics.boundedLength(-3.0D, 1.0D));
    }
}
