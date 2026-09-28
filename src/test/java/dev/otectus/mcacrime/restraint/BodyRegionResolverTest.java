package dev.otectus.mcacrime.restraint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which body region an interaction landed on, on bodies that are not a standing vanilla player
 * (0.7.5 M2.4).
 *
 * <p>Upstream splits the body at absolute world heights of 1.5 and 0.33 blocks. Those are the right
 * numbers for exactly one body. A crouching player is 1.5 blocks tall, so on them the head region
 * begins at the crown and is unreachable; a short MCA child or a scaled settlement rig is wrong the
 * same way, and a tall one has its "legs" reaching most of the way up its torso.
 *
 * <p>The fix is to normalise by the subject's own height, and these tests are that claim: the same
 * proportion of any body resolves to the same region, and a standing player still splits where it
 * always did.
 */
class BodyRegionResolverTest {

    private static final double STANDING = 1.8D;
    private static final double CROUCHING = 1.5D;
    private static final double HALF = 0.9D;
    private static final double DOUBLE = 3.6D;

    private static RestraintSlot at(double height, double rigHeight) {
        return BodyRegionResolver.resolve(height, rigHeight, RigProfile.vanillaHumanoid())
                .slot().orElseThrow();
    }

    @Test
    void aStandingPlayerSplitsWhereItAlwaysDid() {
        // The source's own practical split, reproduced: above 1.5 is head, at or below 0.33 is legs.
        assertEquals(RestraintSlot.HEAD, at(1.7D, STANDING));
        assertEquals(RestraintSlot.ARMS, at(1.0D, STANDING));
        assertEquals(RestraintSlot.LEGS, at(0.2D, STANDING));
        assertEquals(RestraintSlot.LEGS, at(0.33D, STANDING));
        assertEquals(RestraintSlot.ARMS, at(0.34D, STANDING));
    }

    @Test
    void crouchingStillHasAHead() {
        // The case the absolute heights lose entirely: at 1.5 tall, nothing was ever above 1.5.
        assertEquals(RestraintSlot.HEAD, at(1.45D, CROUCHING));
        assertEquals(RestraintSlot.ARMS, at(0.8D, CROUCHING));
        assertEquals(RestraintSlot.LEGS, at(0.1D, CROUCHING));
    }

    @Test
    void halfAndDoubleHeightRigsSplitAtTheSameProportions() {
        for (double height : new double[] {HALF, DOUBLE}) {
            assertEquals(RestraintSlot.HEAD, at(height * 0.95D, height));
            assertEquals(RestraintSlot.ARMS, at(height * 0.5D, height));
            assertEquals(RestraintSlot.LEGS, at(height * 0.05D, height));
        }
    }

    @Test
    void aRigWithoutTheRegionSaysSo_ratherThanDefaulting() {
        // A settlement life stage with no arms or legs. The answer is an explicit reason, because a
        // defaulted slot is how gear ends up on a body that cannot take it off again.
        RigProfile headOnly = new RigProfile("grub", false, true, false, false, 1.0F);
        BodyRegionResolver.Region region = BodyRegionResolver.resolve(0.9D, STANDING, headOnly);
        assertFalse(region.resolved());
        assertEquals(BodyRegionResolver.Unavailable.REGION_MISSING, region.reason());

        // The head it does have still resolves.
        assertTrue(BodyRegionResolver.resolve(1.7D, STANDING, headOnly).resolved());
    }

    @Test
    void aHitOutsideTheBodyIsRefused_notClamped() {
        BodyRegionResolver.Region above =
                BodyRegionResolver.resolve(4.0D, STANDING, RigProfile.vanillaHumanoid());
        assertEquals(BodyRegionResolver.Unavailable.OUT_OF_BOUNDS, above.reason());
        BodyRegionResolver.Region below =
                BodyRegionResolver.resolve(-1.0D, STANDING, RigProfile.vanillaHumanoid());
        assertEquals(BodyRegionResolver.Unavailable.OUT_OF_BOUNDS, below.reason());
    }

    @Test
    void anUnreadableRigIsNeverASlot() {
        assertEquals(BodyRegionResolver.Unavailable.NO_RIG,
                BodyRegionResolver.resolve(1.0D, 0.0D, RigProfile.vanillaHumanoid()).reason());
        assertEquals(BodyRegionResolver.Unavailable.NO_RIG,
                BodyRegionResolver.resolve(Double.NaN, STANDING, RigProfile.vanillaHumanoid()).reason());
    }

    @Test
    void anExplicitRequestIsStillCheckedAgainstTheRig() {
        // The self panel sends a slot rather than an aim, which is the correction to upstream's
        // degrees-against-radians pitch test -- but a request is still only a request.
        RigProfile headOnly = new RigProfile("grub", false, true, false, false, 1.0F);
        assertTrue(BodyRegionResolver.requested(RestraintSlot.HEAD, headOnly).resolved());
        assertFalse(BodyRegionResolver.requested(RestraintSlot.LEGS, headOnly).resolved());
        assertFalse(BodyRegionResolver.requested(null, RigProfile.vanillaHumanoid()).resolved());
    }
}
