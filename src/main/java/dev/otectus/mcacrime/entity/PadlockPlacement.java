package dev.otectus.mcacrime.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Where a padlock hangs on the block it locks (M3.4).
 *
 * <p>A {@link PadlockEntity}'s {@code pos} is the locked block itself, because that is what its lock
 * targets. Vanilla's {@code HangingEntity} placement assumes the opposite convention -- {@code pos} is
 * the air block in front of the support, and the entity sits 0.47 back from its centre -- so applied
 * to the locked block it hung every padlock on the far side of what it locked, and on a cell door
 * drawn in the middle of its block it left the padlock floating clear of the door altogether.
 *
 * <p>Here the padlock is drawn on the actual surface of the block's closed shape, just in front of it,
 * and on a door at its lock plate. The entity's own position stays inside {@code pos} (see
 * {@link #anchor}) because {@code HangingEntity} re-derives its block from its position on several
 * paths; the renderer carries the difference. Pure, so every rule is asserted without a level.
 */
public final class PadlockPlacement {

    /** How far in front of the surface the padlock is drawn, so the two never z-fight. */
    public static final double OUTSET = 1.0D / 16.0D;
    /** From a door's centre line toward its lock plate, which the cell door draws 13/16 across. */
    public static final double PLATE_OFFSET = 5.0D / 16.0D;
    /** Below the seam between a door's halves; the cell door's lock plate straddles that seam. */
    public static final double DOOR_DROP = 2.0D / 16.0D;
    /** How far inside {@code pos} the entity's position is kept. */
    private static final double INSET = 1.0D / 1024.0D;

    private static final double HALF_WIDTH = 3.0D / 16.0D;
    private static final double HALF_HEIGHT = 4.0D / 16.0D;
    private static final double HALF_DEPTH = 1.0D / 32.0D;

    private static final AABB FULL = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

    /**
     * What a door contributes: which half {@code pos} is, and which side of the door its plate is on.
     *
     * @param upper     whether {@code pos} is the door's upper half
     * @param plateSide the horizontal direction, from the door's centre line, of its lock plate
     */
    public record Door(boolean upper, Direction plateSide) {
    }

    private PadlockPlacement() {
    }

    /**
     * Where the padlock is drawn, in world coordinates.
     *
     * @param pos    the locked block
     * @param hangs  the horizontal direction the padlock faces, away from the block
     * @param bounds the block's closed shape in block-local coordinates, or null for a full block
     * @param door   the door the padlock hangs on, or null for anything else
     */
    public static Vec3 visual(BlockPos pos, Direction hangs, @Nullable AABB bounds, @Nullable Door door) {
        AABB shape = bounds == null || bounds.getSize() <= 0.0D ? FULL : bounds;
        double[] at = {
                (shape.minX + shape.maxX) / 2.0D,
                (shape.minY + shape.maxY) / 2.0D,
                (shape.minZ + shape.maxZ) / 2.0D};
        Direction.Axis normal = hangs.getAxis();
        double surface = hangs.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? shape.max(normal) : shape.min(normal);
        at[normal.ordinal()] = surface + hangs.getAxisDirection().getStep() * OUTSET;
        if (door != null && door.plateSide().getAxis() != normal) {
            Direction plate = door.plateSide();
            at[plate.getAxis().ordinal()] = 0.5D + plate.getAxisDirection().getStep() * PLATE_OFFSET;
            at[1] = door.upper() ? -DOOR_DROP : 1.0D - DOOR_DROP;
        }
        return new Vec3(pos.getX() + at[0], pos.getY() + at[1], pos.getZ() + at[2]);
    }

    /**
     * The entity's own position: the drawn position pulled just inside {@code pos}.
     *
     * <p>{@code HangingEntity.setPos} turns a position back into a block, and that block has to stay
     * the locked one, or the next reposition would hang the padlock on its neighbour.
     */
    public static Vec3 anchor(BlockPos pos, Vec3 visual) {
        return new Vec3(clamp(visual.x, pos.getX()), clamp(visual.y, pos.getY()), clamp(visual.z, pos.getZ()));
    }

    /** The padlock's hit box, around where it is drawn, thin along the way it faces. */
    public static AABB box(Vec3 visual, Direction hangs) {
        double halfX = hangs.getAxis() == Direction.Axis.X ? HALF_DEPTH : HALF_WIDTH;
        double halfZ = hangs.getAxis() == Direction.Axis.Z ? HALF_DEPTH : HALF_WIDTH;
        return new AABB(visual.x - halfX, visual.y - HALF_HEIGHT, visual.z - halfZ,
                visual.x + halfX, visual.y + HALF_HEIGHT, visual.z + halfZ);
    }

    /**
     * Which face of a door a padlock hangs on.
     *
     * <p>Always one of the door's two broad faces. A click on the door's edge or top hangs it on the
     * face the player is standing in front of, rather than on the edge, where it would sit in the
     * doorway.
     *
     * @param clicked     the face that was clicked
     * @param doorFacing  the door's {@code facing}
     * @param towardActor from the door's centre to the player
     */
    public static Direction doorSide(Direction clicked, Direction doorFacing, Vec3 towardActor) {
        Direction.Axis axis = doorFacing.getAxis();
        if (clicked != null && clicked.getAxis() == axis) {
            return clicked;
        }
        double along = axis.choose(towardActor.x, towardActor.y, towardActor.z);
        return along * doorFacing.getAxisDirection().getStep() >= 0.0D ? doorFacing : doorFacing.getOpposite();
    }

    /**
     * Where a door's lock plate is, from its facing and hinge.
     *
     * <p>The cell door's plate is drawn clockwise of its facing for a left hinge -- the latch side of
     * its barred models, and the side vanilla's door model puts the texture's plate on. Any other door
     * is a vanilla one, whose handle is on the latch side vanilla's own hinge implies, the opposite.
     */
    public static Direction plateSide(Direction facing, boolean leftHinge, boolean cellDoor) {
        return cellDoor == leftHinge ? facing.getClockWise() : facing.getCounterClockWise();
    }

    private static double clamp(double value, int block) {
        return Math.max(block + INSET, Math.min(block + 1.0D - INSET, value));
    }
}
