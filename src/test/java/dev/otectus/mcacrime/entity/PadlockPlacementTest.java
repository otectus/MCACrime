package dev.otectus.mcacrime.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a padlock hangs (M3.4).
 *
 * <p>The padlock used vanilla's item-frame arithmetic, which assumes the entity's block is the air in
 * front of the support. A padlock's block is the locked block itself, so every padlock hung 0.47 back
 * from that block's centre -- on the far side of a chest, and well clear of a cell door drawn in the
 * middle of its block. These pin the replacement: on the surface, at the plate, and with the entity's
 * own position never leaving the locked block.
 */
class PadlockPlacementTest {

    private static final BlockPos POS = new BlockPos(10, 64, -5);
    private static final double EPS = 1.0E-9D;

    private static Vec3 local(Vec3 world) {
        return world.subtract(POS.getX(), POS.getY(), POS.getZ());
    }

    private static void near(double expected, double actual, String what) {
        assertEquals(expected, actual, EPS, what);
    }

    @Test
    void aFullBlockIsHungOnTheClickedFaceNotTheFarOne() {
        Vec3 north = local(PadlockPlacement.visual(POS, Direction.NORTH, null, null));
        near(-PadlockPlacement.OUTSET, north.z, "just in front of the north face");
        near(0.5D, north.x, "centred across the face");
        near(0.5D, north.y, "centred up the face");
        Vec3 east = local(PadlockPlacement.visual(POS, Direction.EAST, null, null));
        near(1.0D + PadlockPlacement.OUTSET, east.x, "just in front of the east face");
    }

    @Test
    void aChestIsHungOnItsOwnFrontNotTheBlockBoundary() {
        // A chest is inset by a pixel and fourteen pixels tall.
        AABB chest = new AABB(1.0D / 16, 0.0D, 1.0D / 16, 15.0D / 16, 14.0D / 16, 15.0D / 16);
        Vec3 at = local(PadlockPlacement.visual(POS, Direction.NORTH, chest, null));
        near(1.0D / 16 - PadlockPlacement.OUTSET, at.z, "on the chest's own front");
        near(7.0D / 16, at.y, "half way up the chest");
    }

    @Test
    void aBarredCellDoorTakesItOnItsLockPlate() {
        // Facing east, left hinge, drawn as the centred barred panel x 7..9.
        AABB bars = new AABB(7.0D / 16, 0.0D, 0.0D, 9.0D / 16, 1.0D, 1.0D);
        Direction plate = PadlockPlacement.plateSide(Direction.EAST, true, true);
        assertEquals(Direction.SOUTH, plate, "clockwise of facing for a left hinge");
        Vec3 front = local(PadlockPlacement.visual(POS, Direction.EAST, bars,
                new PadlockPlacement.Door(false, plate)));
        near(9.0D / 16 + PadlockPlacement.OUTSET, front.x, "on the face of the panel itself");
        near(0.5D + PadlockPlacement.PLATE_OFFSET, front.z, "across at the plate");
        near(1.0D - PadlockPlacement.DOOR_DROP, front.y, "at the seam between the halves");
        Vec3 back = local(PadlockPlacement.visual(POS, Direction.WEST, bars,
                new PadlockPlacement.Door(false, plate)));
        near(7.0D / 16 - PadlockPlacement.OUTSET, back.x, "the other face of the same panel");
    }

    @Test
    void anUpperHalfHangsItAtTheSameSeam() {
        AABB bars = new AABB(0.0D, 0.0D, 7.0D / 16, 1.0D, 1.0D, 9.0D / 16);
        Vec3 fromUpper = local(PadlockPlacement.visual(POS, Direction.NORTH, bars,
                new PadlockPlacement.Door(true, Direction.EAST)));
        near(-PadlockPlacement.DOOR_DROP, fromUpper.y, "just below the upper half, at the seam");
    }

    @Test
    void theEntityPositionNeverLeavesTheLockedBlock() {
        for (Direction hangs : Direction.Plane.HORIZONTAL) {
            Vec3 visual = PadlockPlacement.visual(POS, hangs, null, null);
            Vec3 anchor = PadlockPlacement.anchor(POS, visual);
            assertEquals(POS, BlockPos.containing(anchor), hangs + ": the anchor stays inside");
        }
        Vec3 seam = PadlockPlacement.visual(POS, Direction.NORTH,
                new AABB(0.0D, 0.0D, 7.0D / 16, 1.0D, 1.0D, 9.0D / 16),
                new PadlockPlacement.Door(true, Direction.EAST));
        assertEquals(POS, BlockPos.containing(PadlockPlacement.anchor(POS, seam)));
    }

    @Test
    void theHitBoxIsWhereItIsDrawnAndThinAlongItsFacing() {
        Vec3 visual = PadlockPlacement.visual(POS, Direction.SOUTH, null, null);
        AABB box = PadlockPlacement.box(visual, Direction.SOUTH);
        assertTrue(box.contains(visual));
        assertTrue(box.getZsize() < box.getXsize(), "thin along the way it faces");
    }

    @Test
    void aDoorPadlockGoesOnTheBroadFaceThePlayerStandsBefore() {
        Vec3 inFront = new Vec3(0.0D, 0.0D, -2.0D);
        Vec3 behind = new Vec3(0.0D, 0.0D, 2.0D);
        assertEquals(Direction.NORTH, PadlockPlacement.doorSide(Direction.NORTH, Direction.NORTH, behind),
                "a click on a broad face is taken as it is");
        assertEquals(Direction.NORTH, PadlockPlacement.doorSide(Direction.EAST, Direction.NORTH, inFront),
                "an edge click goes to the face the player is in front of");
        assertEquals(Direction.SOUTH, PadlockPlacement.doorSide(Direction.UP, Direction.NORTH, behind));
    }

    @Test
    void theCellDoorAndVanillaDoorsPutTheirPlatesOnOppositeSides() {
        assertEquals(Direction.SOUTH, PadlockPlacement.plateSide(Direction.EAST, true, true));
        assertEquals(Direction.NORTH, PadlockPlacement.plateSide(Direction.EAST, false, true));
        assertEquals(Direction.NORTH, PadlockPlacement.plateSide(Direction.EAST, true, false),
                "a vanilla door's handle is on the latch, opposite its hinge");
        assertEquals(Direction.SOUTH, PadlockPlacement.plateSide(Direction.EAST, false, false));
    }
}
