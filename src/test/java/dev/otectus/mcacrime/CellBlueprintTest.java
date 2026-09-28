package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.CellBlueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cell's geometry.
 *
 * <p>This is the only part of the block-placing code that can be tested without a world, and it happens
 * to be the part with the failure modes that matter: a hole in the cage, an interior with nowhere to
 * stand, a door that is not where the confinement region expects a wall, and — the subtle one — a
 * structure that does not fit inside the region {@code JailRegion.contains} checks, which would leave
 * part of the cage unprotected from the prisoner holding a pickaxe.
 */
class CellBlueprintTest {

    private static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.EAST,
            Direction.SOUTH, Direction.WEST);

    @Test
    void everyPositionIsPlacedExactlyOnce() {
        for (Direction facing : SIDES) {
            Set<String> seen = new HashSet<>();
            for (CellBlueprint.Placement p : CellBlueprint.placements(facing)) {
                assertTrue(seen.add(p.dx() + "," + p.dy() + "," + p.dz()),
                        "duplicate position in the " + facing + " blueprint at "
                                + p.dx() + "," + p.dy() + "," + p.dz());
            }
        }
    }

    /**
     * The whole structure must fit inside the Chebyshev cube of {@link CellBlueprint#RADIUS} centred one
     * block above the anchor — which is the box {@code HoldingCell.toAnchor()} hands to the confinement
     * check. A roof outside that box is a roof {@code ContainmentHandler} will happily let a prisoner
     * mine through.
     */
    @Test
    void structureFitsTheConfinementRegion() {
        int r = CellBlueprint.RADIUS;
        for (CellBlueprint.Placement p : CellBlueprint.placements()) {
            int dyFromRegionCentre = p.dy() - 1; // the region is centred one above the anchor
            assertTrue(Math.abs(p.dx()) <= r && Math.abs(p.dz()) <= r && Math.abs(dyFromRegionCentre) <= r,
                    "placement at " + p.dx() + "," + p.dy() + "," + p.dz()
                            + " falls outside the confinement region");
        }
    }

    @Test
    void theInteriorIsOpenAndTallEnoughToStandIn() {
        long interior = CellBlueprint.placements().stream()
                .filter(p -> p.role() == CellBlueprint.Role.INTERIOR).count();
        int side = CellBlueprint.INTERIOR_RADIUS * 2 + 1;
        assertEquals((long) side * side * CellBlueprint.INTERIOR_HEIGHT, interior);
        assertTrue(CellBlueprint.INTERIOR_HEIGHT >= 2, "a prisoner has to fit standing up");
    }

    /**
     * No gap in the walls: every wall column is solid at every interior height, whether that solid is
     * a pillar, bars or the door. The door is a way out only once its padlock has been dealt with; a
     * missing block is a way out for free.
     */
    @Test
    void theWallsAreComplete() {
        int r = CellBlueprint.RADIUS;
        for (Direction facing : SIDES) {
            List<CellBlueprint.Placement> placements = CellBlueprint.placements(facing);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!CellBlueprint.isWallColumn(dx, dz)) {
                        continue;
                    }
                    for (int dy = 0; dy < CellBlueprint.INTERIOR_HEIGHT; dy++) {
                        int fx = dx;
                        int fy = dy;
                        int fz = dz;
                        assertTrue(placements.stream().anyMatch(p -> p.dx() == fx && p.dy() == fy
                                        && p.dz() == fz && p.role() != CellBlueprint.Role.INTERIOR),
                                "wall gap at " + fx + "," + fy + "," + fz + " facing " + facing);
                    }
                }
            }
        }
    }

    /** The four corners are stone pillars; everything else in the walls is bars or the door. */
    @Test
    void cornersArePillarsAndTheRestOfTheWallIsBars() {
        for (CellBlueprint.Placement p : CellBlueprint.placements()) {
            if (p.dy() < 0 || p.dy() >= CellBlueprint.INTERIOR_HEIGHT || !CellBlueprint.isWallColumn(p.dx(), p.dz())) {
                continue;
            }
            if (CellBlueprint.isCornerColumn(p.dx(), p.dz())) {
                assertEquals(CellBlueprint.Role.PILLAR, p.role(), "corner at " + p.dx() + "," + p.dz());
            } else {
                assertTrue(p.role() == CellBlueprint.Role.BARS || p.role().isDoor(),
                        "wall block at " + p.dx() + "," + p.dy() + "," + p.dz() + " is " + p.role());
            }
        }
    }

    /**
     * Exactly one door, two halves high, in the middle of the wall it faces, with bars above it.
     *
     * <p>The middle of a wall is never a corner, so the door is always flanked by bars and reads as
     * "in bars"; and it is at the prisoner's feet, so its lower half is the block they walk through.
     */
    @Test
    void thereIsOneDoorInTheMiddleOfTheFacingWall() {
        for (Direction facing : SIDES) {
            List<CellBlueprint.Placement> placements = CellBlueprint.placements(facing);
            List<CellBlueprint.Placement> door = placements.stream().filter(p -> p.role().isDoor()).toList();
            assertEquals(2, door.size(), "one door of two halves, facing " + facing);
            int doorX = CellBlueprint.doorX(facing);
            int doorZ = CellBlueprint.doorZ(facing);
            assertEquals(facing.getStepX() * CellBlueprint.RADIUS, doorX);
            assertEquals(facing.getStepZ() * CellBlueprint.RADIUS, doorZ);
            assertFalse(CellBlueprint.isCornerColumn(doorX, doorZ), "the door is never a corner");
            assertTrue(CellBlueprint.isWallColumn(doorX, doorZ), "the door is in the wall");
            for (CellBlueprint.Placement half : door) {
                assertEquals(doorX, half.dx());
                assertEquals(doorZ, half.dz());
                assertEquals(half.role() == CellBlueprint.Role.DOOR_LOWER ? 0 : 1, half.dy(),
                        "the lower half is at the prisoner's feet, the upper one above it");
            }
            assertTrue(placements.stream().anyMatch(p -> p.dx() == doorX && p.dz() == doorZ && p.dy() == 2
                            && p.role() == CellBlueprint.Role.BARS),
                    "bars above the door, facing " + facing);
        }
    }

    @Test
    void thereIsAFloorAndARoofUnderAndOverTheWholeFootprintAndExactlyOneLight() {
        int side = CellBlueprint.RADIUS * 2 + 1;
        long floor = CellBlueprint.placements().stream()
                .filter(p -> p.role() == CellBlueprint.Role.FLOOR).count();
        long roof = CellBlueprint.placements().stream()
                .filter(p -> p.role() == CellBlueprint.Role.ROOF).count();
        long lights = CellBlueprint.placements().stream()
                .filter(p -> p.role() == CellBlueprint.Role.LIGHT).count();
        assertEquals((long) side * side, floor);
        assertEquals((long) side * side - 1, roof);
        assertEquals(1L, lights, "a dark cell is a mob spawner");
    }

    /**
     * The door goes in after every bar and before the interior is cleared.
     *
     * <p>After the bars, so the door's in-bars shape and the bars' column texture see their final
     * neighbours before the builder writes the journal; before the interior, for the same reason every
     * structural block is.
     */
    @Test
    void theDoorIsPlacedAfterTheBarsAndBeforeTheInterior() {
        List<CellBlueprint.Placement> placements = CellBlueprint.placements();
        int lastBar = -1;
        int firstDoor = Integer.MAX_VALUE;
        int lastDoor = -1;
        int firstInterior = Integer.MAX_VALUE;
        for (int i = 0; i < placements.size(); i++) {
            CellBlueprint.Role role = placements.get(i).role();
            if (role == CellBlueprint.Role.BARS || role == CellBlueprint.Role.PILLAR
                    || role == CellBlueprint.Role.ROOF || role == CellBlueprint.Role.LIGHT
                    || role == CellBlueprint.Role.FLOOR) {
                lastBar = Math.max(lastBar, i);
            } else if (role.isDoor()) {
                firstDoor = Math.min(firstDoor, i);
                lastDoor = Math.max(lastDoor, i);
            } else {
                firstInterior = Math.min(firstInterior, i);
            }
        }
        assertTrue(firstDoor > lastBar, "the door must follow the bars that flank it");
        assertTrue(firstInterior > lastDoor,
                "the interior must be cleared last, or a wall block lands in space just opened");
    }

    @Test
    void wallColumnsAndInteriorColumnsDoNotOverlap() {
        assertFalse(CellBlueprint.isWallColumn(0, 0));
        assertTrue(CellBlueprint.isWallColumn(CellBlueprint.RADIUS, 0));
        assertFalse(CellBlueprint.isCornerColumn(CellBlueprint.RADIUS, 0));
        assertTrue(CellBlueprint.isCornerColumn(CellBlueprint.RADIUS, -CellBlueprint.RADIUS));
    }

    /** The footprint is the same whichever wall the door is in, so one site check serves every facing. */
    @Test
    void theFootprintDoesNotDependOnTheFacing() {
        Set<String> east = new HashSet<>();
        CellBlueprint.placements(Direction.EAST).forEach(p -> east.add(p.dx() + "," + p.dy() + "," + p.dz()));
        for (Direction facing : SIDES) {
            Set<String> other = new HashSet<>();
            CellBlueprint.placements(facing).forEach(p -> other.add(p.dx() + "," + p.dy() + "," + p.dz()));
            assertEquals(east, other, "footprint facing " + facing);
        }
    }

    /** The door faces whoever brought the prisoner: the larger horizontal offset wins, x on a tie. */
    @Test
    void theDoorFacesTheArrest() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        assertEquals(Direction.EAST, CellBlueprint.facingToward(anchor, new BlockPos(5, 64, 2)));
        assertEquals(Direction.WEST, CellBlueprint.facingToward(anchor, new BlockPos(-5, 70, 2)));
        assertEquals(Direction.SOUTH, CellBlueprint.facingToward(anchor, new BlockPos(1, 64, 4)));
        assertEquals(Direction.NORTH, CellBlueprint.facingToward(anchor, new BlockPos(1, 64, -4)));
        assertEquals(Direction.EAST, CellBlueprint.facingToward(anchor, new BlockPos(3, 64, 3)), "x wins a tie");
        assertEquals(CellBlueprint.DEFAULT_FACING, CellBlueprint.facingToward(anchor, anchor));
        assertEquals(CellBlueprint.DEFAULT_FACING, CellBlueprint.facingToward(anchor, null));
        assertEquals(CellBlueprint.DEFAULT_FACING, CellBlueprint.horizontal(Direction.UP),
                "a door is never in the floor or the roof");
    }
}
