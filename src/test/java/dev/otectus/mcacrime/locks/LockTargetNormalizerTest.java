package dev.otectus.mcacrime.locks;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One lock per multipart group, whichever half you are holding (M3.1, spec §10.2).
 *
 * <p>The rule under test is deliberately arithmetic rather than a block-state question: the same total
 * order decides for a door, a double chest and any two-block device, and it keeps deciding after the
 * block has already been replaced by air — which is the exact moment a break handler needs an answer.
 */
class LockTargetNormalizerTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void doorHalvesAndDoubleChest() {
        BlockPos lower = new BlockPos(10, 64, 10);
        BlockPos upper = lower.above();
        assertEquals(lower, LockTargetNormalizer.canonicalPos(lower, upper),
                "the lower half is the canonical half of a door");
        assertEquals(lower, LockTargetNormalizer.canonicalPos(upper, lower),
                "and asking from the upper half gives the same answer");

        BlockPos west = new BlockPos(4, 70, 8);
        BlockPos east = new BlockPos(5, 70, 8);
        assertEquals(west, LockTargetNormalizer.canonicalPos(west, east));
        assertEquals(west, LockTargetNormalizer.canonicalPos(east, west));

        BlockPos north = new BlockPos(4, 70, 8);
        BlockPos south = new BlockPos(4, 70, 9);
        assertEquals(north, LockTargetNormalizer.canonicalPos(south, north));

        assertTrue(LockTargetNormalizer.sameGroup(
                LockTargetNormalizer.canonical(OVERWORLD, upper, lower),
                LockTargetNormalizer.canonical(OVERWORLD, lower, upper)),
                "both halves of one door are one lock group");
        assertTrue(LockTargetNormalizer.sameGroup(
                LockTargetNormalizer.canonical(OVERWORLD, east, west),
                LockTargetNormalizer.canonical(OVERWORLD, west, east)),
                "both halves of one double chest are one lock group");
    }

    @Test
    void aSingleBlockIsItsOwnCanonicalPosition() {
        BlockPos pos = new BlockPos(-3, 12, 400);
        assertEquals(pos, LockTargetNormalizer.canonicalPos(pos, null));
        assertEquals(pos, LockTargetNormalizer.canonicalPos(pos, pos));
    }

    @Test
    void differentDimensionsAreNeverTheSameGroup() {
        BlockPos pos = new BlockPos(0, 64, 0);
        LockTarget overworld = LockTargetNormalizer.canonical(OVERWORLD, pos, null);
        LockTarget nether = LockTargetNormalizer.canonical(ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"),
                pos, null);
        assertFalse(LockTargetNormalizer.sameGroup(overworld, nether),
                "the same coordinates in two worlds are two places");
    }

    @Test
    void aLostTargetMatchesNothingIncludingAnotherLostTarget() {
        assertFalse(LockTargetNormalizer.sameGroup(LockTarget.none(), LockTarget.none()),
                "'this lock has no target' is not a group two locks can share");
        assertFalse(LockTargetNormalizer.sameGroup(null, LockTarget.none()));
    }

    @Test
    void entityTargetsCompareByIdentity() {
        java.util.UUID id = java.util.UUID.randomUUID();
        assertTrue(LockTargetNormalizer.sameGroup(LockTarget.entity(id), LockTarget.entity(id)));
        assertFalse(LockTargetNormalizer.sameGroup(LockTarget.entity(id),
                LockTarget.entity(java.util.UUID.randomUUID())));
    }
}
