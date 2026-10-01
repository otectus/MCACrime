package dev.otectus.mcacrime.block;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * An open barred cell door is solid where it is drawn.
 *
 * <p>The barred models hang the door on the counter-clockwise end of its plane for a left hinge and
 * swing it back away from its facing ({@code cell_door_bars_bottom_left_open}: x -7..9, z 0..2 for a
 * door facing east). Vanilla's open shape assumes the other hinge, so an open barred door drew against
 * one side of its doorway and blocked the other. Pinned against the model's own coordinates, clipped
 * to the block.
 */
class CellDoorOpenShapeTest {

    private static final boolean LEFT = true;
    private static final boolean RIGHT = false;

    private static double[] box(Direction facing, boolean leftHinge) {
        return CellDoorShapes.openInBars(facing, leftHinge);
    }

    private static double[] px(double x1, double z1, double x2, double z2) {
        return new double[] {x1, 0.0D, z1, x2, 16.0D, z2};
    }

    @Test
    void eastFacingMatchesTheModelsAsAuthored() {
        assertArrayEquals(px(0, 0, 9, 2), box(Direction.EAST, LEFT), "left: hinge on the north end");
        assertArrayEquals(px(0, 14, 9, 16), box(Direction.EAST, RIGHT), "right: hinge on the south end");
    }

    @Test
    void theOtherFacingsAreTheBlockstateRotationsOfIt() {
        // facing=north is y=270, south y=90, west y=180 in cell_door.json.
        assertArrayEquals(px(0, 7, 2, 16), box(Direction.NORTH, LEFT));
        assertArrayEquals(px(14, 0, 16, 9), box(Direction.SOUTH, LEFT));
        assertArrayEquals(px(7, 14, 16, 16), box(Direction.WEST, LEFT));
        assertArrayEquals(px(14, 7, 16, 16), box(Direction.NORTH, RIGHT));
    }
}
