package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jail-region geometry (spec §7.3): a Chebyshev cube within one dimension. */
class JailRegionTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");
    private static final BlockPos ANCHOR = new BlockPos(0, 64, 0);

    @Test
    void insideTheCube() {
        assertTrue(JailRegion.contains(ANCHOR, 5, OVERWORLD, new BlockPos(3, 66, -2), OVERWORLD));
        assertTrue(JailRegion.contains(ANCHOR, 5, OVERWORLD, new BlockPos(5, 69, 5), OVERWORLD)); // all on the edge
    }

    @Test
    void outsideTheCube() {
        assertFalse(JailRegion.contains(ANCHOR, 5, OVERWORLD, new BlockPos(6, 64, 0), OVERWORLD)); // dx = 6
        assertFalse(JailRegion.contains(ANCHOR, 5, OVERWORLD, new BlockPos(0, 70, 0), OVERWORLD)); // dy = 6
    }

    @Test
    void dimensionMismatchIsNeverContained() {
        assertFalse(JailRegion.contains(ANCHOR, 5, OVERWORLD, ANCHOR, NETHER));
    }

    @Test
    void zeroRadiusIsAnchorBlockOnly() {
        assertTrue(JailRegion.contains(ANCHOR, 0, OVERWORLD, ANCHOR, OVERWORLD));
        assertFalse(JailRegion.contains(ANCHOR, 0, OVERWORLD, ANCHOR.above(), OVERWORLD));
    }

    @Test
    void nullsAreNeverContained() {
        assertFalse(JailRegion.contains(null, 5, OVERWORLD, ANCHOR, OVERWORLD));
        assertFalse(JailRegion.contains(ANCHOR, 5, null, ANCHOR, OVERWORLD));
    }
}
