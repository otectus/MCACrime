package dev.otectus.mcacrime.jail;

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
 * <p>A 3x3 interior three blocks tall, ringed by iron bars, floored and roofed. Offsets are measured
 * from the prisoner's feet: the floor course sits at -1 and the roof at +3, so the structure spans five
 * blocks vertically and {@link RADIUS} is exactly its half-extent about its own middle. That is why
 * {@link HoldingCell#toAnchor()} centres the confinement region one block above the stored anchor --
 * a cube centred on the prisoner's feet would leave the roof outside it, and {@code ContainmentHandler}
 * would happily let them mine the ceiling out.
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

    /** What a position in the blueprint is for. */
    public enum Role {
        /** Solid ground under the cell, so a prisoner cannot dig down and nothing spawns below. */
        FLOOR,
        /** Iron bars: the cell is meant to be seen out of and seen into. */
        BARS,
        /** A single roof block that emits light, so the cell is not a mob spawner. */
        LIGHT,
        /** Cleared to air, so building into a hillside still leaves somewhere to stand. */
        INTERIOR
    }

    /** One block of the blueprint, as an offset from the anchor. */
    public record Placement(int dx, int dy, int dz, Role role) {
    }

    private CellBlueprint() {
    }

    /**
     * Every block the cell occupies, in placement order: floor, then walls, then roof, then the
     * interior cleared last so a wall block can never be placed into the space just cleared.
     * Restoration replays this list in reverse.
     */
    public static List<Placement> placements() {
        List<Placement> out = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                out.add(new Placement(dx, -1, dz, Role.FLOOR));
            }
        }
        for (int dy = 0; dy < INTERIOR_HEIGHT; dy++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (isWallColumn(dx, dz)) {
                        out.add(new Placement(dx, dy, dz, Role.BARS));
                    }
                }
            }
        }
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                // The roof is barred except for its centre, which is the light. Glowstone rather than a
                // lantern or a torch because it is a full block with no attachment rules to satisfy —
                // a hanging lantern under iron bars is exactly the kind of placement that silently
                // fails and leaves a dark cell.
                out.add(new Placement(dx, INTERIOR_HEIGHT, dz,
                        dx == 0 && dz == 0 ? Role.LIGHT : Role.BARS));
            }
        }
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

    /** The lowest offset the blueprint touches, relative to the anchor. */
    public static int lowestOffset() {
        return -1;
    }

    /** The highest offset the blueprint touches, relative to the anchor. */
    public static int highestOffset() {
        return INTERIOR_HEIGHT;
    }
}
