package dev.otectus.mcacrime.jail;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Pure jail-region geometry (spec §7.3): a Chebyshev cube of {@code radius} around an anchor, in a single
 * dimension. Cube (not sphere) so it maps to block-aligned walls and stays cheap. No game/server deps so
 * it's unit-testable.
 */
public final class JailRegion {

    private JailRegion() {
    }

    public static boolean contains(BlockPos anchor, int radius, ResourceLocation dim,
                                   BlockPos pos, ResourceLocation posDim) {
        if (anchor == null || dim == null || posDim == null || pos == null) {
            return false;
        }
        if (!dim.equals(posDim)) {
            return false;
        }
        int r = Math.max(0, radius);
        return Math.abs(pos.getX() - anchor.getX()) <= r
                && Math.abs(pos.getY() - anchor.getY()) <= r
                && Math.abs(pos.getZ() - anchor.getZ()) <= r;
    }
}
