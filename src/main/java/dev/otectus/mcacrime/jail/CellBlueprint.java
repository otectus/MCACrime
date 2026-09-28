package dev.otectus.mcacrime.jail;

import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a holding cell, as pure geometry.
 *
 * <p>Separated from {@link CellBuilder} so the shape can be reasoned about and unit-tested without a
 * world: the interesting failure modes of a builder are "it left a hole", "it walled the prisoner into
 * one block", and "the region the confinement check uses does not match the box that was actually
 * built", and all three are questions about offsets rather than about blocks.
 *
 * <p>A 3x3 interior three blocks tall inside a 5x5 footprint: a reinforced-stone floor, four
 * reinforced-stone corner pillars, reinforced bars between them, a two-block cell door in the middle
 * of the front wall, and a reinforced-stone roof with a lamp at its centre. Offsets are measured from
 * the prisoner's feet: the floor course sits at -1 and the roof at +3, so the structure spans five
 * blocks vertically and {@link #RADIUS} is exactly its half-extent about its own middle. That is why
 * {@link HoldingCell#toAnchor()} centres the confinement region one block above the stored anchor --
 * a cube centred on the prisoner's feet would leave the roof outside it, and {@code ContainmentHandler}
 * would happily let them mine the ceiling out.
 *
 * <p>The door is the one intended way out. It is placed after the bars that flank it, so its
 * in-bars shape and the bars' column texture settle before the builder writes its journal, and the
 * padlock that holds it shut is the builder's business rather than the blueprint's.
 *
 * <p>3×3 rather than 1×1 deliberately. A single-block cage is the cheaper structure and reads, in
 * play, as being stuck in a block rather than as being held: there is nowhere to stand, the walls are
 * inside arm's reach on every side, and it is indistinguishable from a bug.
 */
public final class CellBlueprint {

    /** Half-width of the whole structure, and the {@link JailAnchor} radius a built cell carries. */
    public static final int RADIUS = 2;

    /** Half-width of the open interior. */
    public static final int INTERIOR_RADIUS = 1;

    /** Interior height in blocks, measured from the anchor upward. */
    public static final int INTERIOR_HEIGHT = 3;

    /** The side the door is on when nobody says otherwise. */
    public static final Direction DEFAULT_FACING = Direction.EAST;

    /** What a position in the blueprint is for. */
    public enum Role {
        /** Solid ground under the cell, so a prisoner cannot dig down and nothing spawns below. */
        FLOOR,
        /** A reinforced-stone corner column: the frame the bars hang between. */
        PILLAR,
        /** Reinforced bars: the cell is meant to be seen out of and seen into. */
        BARS,
        /** The lower half of the cell door, at the prisoner's feet. */
        DOOR_LOWER,
        /** The upper half of the cell door. */
        DOOR_UPPER,
        /** The reinforced-stone roof. */
        ROOF,
        /** A single roof block that emits light, so the cell is not a mob spawner. */
        LIGHT,
        /** Cleared to air, so building into a hillside still leaves somewhere to stand. */
        INTERIOR;

        /** True for the two halves of the door. */
        public boolean isDoor() {
            return this == DOOR_LOWER || this == DOOR_UPPER;
        }
    }

    /** One block of the blueprint, as an offset from the anchor. */
    public record Placement(int dx, int dy, int dz, Role role) {
    }

    private CellBlueprint() {
    }

    /** Includes the floor, walls, roof and enclosed air, not just the blocks being replaced. */
    public static net.minecraft.world.phys.AABB bounds(net.minecraft.core.BlockPos anchor) {
        return new net.minecraft.world.phys.AABB(anchor.getX() - RADIUS, anchor.getY() - 1,
                anchor.getZ() - RADIUS, anchor.getX() + RADIUS + 1,
                anchor.getY() + INTERIOR_HEIGHT + 1, anchor.getZ() + RADIUS + 1);
    }

    /** The blueprint with the door on its default side. The footprint is the same for every facing. */
    public static List<Placement> placements() {
        return placements(DEFAULT_FACING);
    }

    /**
     * Every block the cell occupies, in placement order: floor, then pillars and bars, then roof,
     * then the two door halves, then the interior cleared last so a wall block can never be placed
     * into the space just cleared. Restoration replays this list in reverse.
     *
     * <p>The door comes after the bars on purpose. A cell door decides whether it sits "in bars" from
     * its neighbours, and reinforced bars decide their column texture from what is above and below;
     * placing the door last means both are settled by the time the builder reads the world back.
     *
     * @param facing which wall carries the door; a vertical direction falls back to
     *               {@link #DEFAULT_FACING}
     */
    public static List<Placement> placements(Direction facing) {
        Direction side = horizontal(facing);
        int doorX = doorX(side);
        int doorZ = doorZ(side);
        List<Placement> out = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                out.add(new Placement(dx, -1, dz, Role.FLOOR));
            }
        }
        for (int dy = 0; dy < INTERIOR_HEIGHT; dy++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (!isWallColumn(dx, dz)) {
                        continue;
                    }
                    if (dx == doorX && dz == doorZ && dy < 2) {
                        continue; // the door, placed after the bars that flank it
                    }
                    out.add(new Placement(dx, dy, dz, isCornerColumn(dx, dz) ? Role.PILLAR : Role.BARS));
                }
            }
        }
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                // The roof is stone except for its centre, which is the lamp: a full block with no
                // attachment rules to satisfy, so it can never silently fail and leave a dark cell.
                out.add(new Placement(dx, INTERIOR_HEIGHT, dz,
                        dx == 0 && dz == 0 ? Role.LIGHT : Role.ROOF));
            }
        }
        out.add(new Placement(doorX, 0, doorZ, Role.DOOR_LOWER));
        out.add(new Placement(doorX, 1, doorZ, Role.DOOR_UPPER));
        for (int dy = 0; dy < INTERIOR_HEIGHT; dy++) {
            for (int dx = -INTERIOR_RADIUS; dx <= INTERIOR_RADIUS; dx++) {
                for (int dz = -INTERIOR_RADIUS; dz <= INTERIOR_RADIUS; dz++) {
                    out.add(new Placement(dx, dy, dz, Role.INTERIOR));
                }
            }
        }
        return out;
    }

    /** True when the column at this horizontal offset is wall rather than interior. */
    public static boolean isWallColumn(int dx, int dz) {
        return Math.abs(dx) > INTERIOR_RADIUS || Math.abs(dz) > INTERIOR_RADIUS;
    }

    /** True for the four corner columns, which are stone pillars rather than bars. */
    public static boolean isCornerColumn(int dx, int dz) {
        return Math.abs(dx) == RADIUS && Math.abs(dz) == RADIUS;
    }

    /** The x offset of the door column for a cell whose door faces {@code facing}. */
    public static int doorX(Direction facing) {
        return horizontal(facing).getStepX() * RADIUS;
    }

    /** The z offset of the door column for a cell whose door faces {@code facing}. */
    public static int doorZ(Direction facing) {
        return horizontal(facing).getStepZ() * RADIUS;
    }

    /**
     * Which wall the door should be in for a cell at {@code anchor} that is being built for somebody
     * standing at {@code toward}: the horizontal axis with the larger offset, and the default when the
     * two coincide. Pure, so a test can pin the tie-break.
     */
    public static Direction facingToward(net.minecraft.core.BlockPos anchor, net.minecraft.core.BlockPos toward) {
        if (anchor == null || toward == null) {
            return DEFAULT_FACING;
        }
        int dx = toward.getX() - anchor.getX();
        int dz = toward.getZ() - anchor.getZ();
        if (dx == 0 && dz == 0) {
            return DEFAULT_FACING;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** A horizontal facing, always: the door cannot be in the floor or the roof. */
    public static Direction horizontal(Direction facing) {
        return facing == null || facing.getAxis().isVertical() ? DEFAULT_FACING : facing;
    }

    /** The lowest offset the blueprint touches, relative to the anchor. */
    public static int lowestOffset() {
        return -1;
    }

    /** The highest offset the blueprint touches, relative to the anchor. */
    public static int highestOffset() {
        return INTERIOR_HEIGHT;
    }
}
