package dev.otectus.mcacrime.block;

import net.minecraft.core.Direction;

/**
 * The cell door's open barred geometry as plain numbers, so it is asserted without a block registry.
 *
 * <p>The barred models hang the door on the counter-clockwise end of its plane for a left hinge and
 * the clockwise end for a right one, and swing it back away from {@code facing}
 * ({@code cell_door_bars_*_open}). Vanilla's open shape assumes the opposite hinge, so an open barred
 * door used to draw against one side of the doorway and block the other.
 */
public final class CellDoorShapes {

    private CellDoorShapes() {
    }

    /**
     * The part of an open barred door inside its block, in pixels, as
     * {@code {minX, minY, minZ, maxX, maxY, maxZ}}: a two-pixel leaf along the hinge edge, reaching from
     * the back of the block to just past its middle.
     */
    public static double[] openInBars(Direction facing, boolean leftHinge) {
        Direction hingeSide = leftHinge ? facing.getCounterClockWise() : facing.getClockWise();
        double[] min = {0.0D, 0.0D, 0.0D};
        double[] max = {16.0D, 16.0D, 16.0D};
        int along = facing.getAxis().ordinal();
        int across = hingeSide.getAxis().ordinal();
        if (facing.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            max[along] = 9.0D;
        } else {
            min[along] = 7.0D;
        }
        if (hingeSide.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            min[across] = 14.0D;
        } else {
            max[across] = 2.0D;
        }
        return new double[] {min[0], min[1], min[2], max[0], max[1], max[2]};
    }
}
