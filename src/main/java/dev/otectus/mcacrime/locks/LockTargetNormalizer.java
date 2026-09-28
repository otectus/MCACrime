package dev.otectus.mcacrime.locks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;

/**
 * One canonical position per multipart target group (spec §10.2).
 *
 * <p>Both halves of a door, both halves of a double chest and both halves of a two-block device are
 * one thing to lock, so they have to be one thing to <em>look up</em>. Everything that stores or finds
 * a lock by position goes through here first; nothing else is allowed to guess.
 *
 * <p>The canonical half is chosen by a fixed total order — lowest y, then lowest x, then lowest z —
 * rather than by asking the block which half it is. That is on purpose: the rule then gives the same
 * answer for a door (whose lower half is the smaller y), a double chest (whose two halves differ on
 * one horizontal axis) and any future two-block device, and it keeps giving the same answer when the
 * block state has already been replaced by air, which is exactly the moment a break handler needs it.
 * The state-reading part is {@link #partner}, and it is separate so the arithmetic can be tested
 * without a level.
 */
public final class LockTargetNormalizer {

    private LockTargetNormalizer() {
    }

    // --- pure -------------------------------------------------------------------------------------

    /**
     * The canonical position of the group containing {@code pos} and its {@code partner}.
     *
     * <p>A null or equal partner is a single block, which is its own canonical position.
     */
    public static BlockPos canonicalPos(BlockPos pos, @Nullable BlockPos partner) {
        if (pos == null) {
            return null;
        }
        if (partner == null || partner.equals(pos)) {
            return pos.immutable();
        }
        return lower(pos, partner).immutable();
    }

    /** The lower of two positions under the fixed total order. */
    private static BlockPos lower(BlockPos a, BlockPos b) {
        if (a.getY() != b.getY()) {
            return a.getY() < b.getY() ? a : b;
        }
        if (a.getX() != b.getX()) {
            return a.getX() < b.getX() ? a : b;
        }
        return a.getZ() <= b.getZ() ? a : b;
    }

    /** The canonical {@link LockTarget} for a block group. */
    public static LockTarget canonical(ResourceLocation dimension, BlockPos pos,
                                       @Nullable BlockPos partner) {
        return LockTarget.block(dimension, canonicalPos(pos, partner));
    }

    /**
     * Whether two targets name the same lockable group.
     *
     * <p>Only meaningful for already-canonical targets, which is why everything that stores one
     * canonicalises first. Entity targets compare by entity id; a {@code NONE} target matches nothing,
     * including another {@code NONE}, because "this lock has lost its target" is not a group.
     */
    public static boolean sameGroup(@Nullable LockTarget a, @Nullable LockTarget b) {
        if (a == null || b == null || a.kind() != b.kind()) {
            return false;
        }
        return switch (a.kind()) {
            case BLOCK -> a.dimension() != null && a.dimension().equals(b.dimension())
                    && a.pos() != null && a.pos().equals(b.pos());
            case ENTITY -> a.entityId() != null && a.entityId().equals(b.entityId());
            case NONE -> false;
        };
    }

    // --- level-facing ------------------------------------------------------------------------------

    /**
     * The other half of {@code pos}'s group, or null when it is a single block.
     *
     * <p>Doors and chests only. Anything else is a single block for locking purposes even when it
     * happens to look like a pair, because inventing a partner for a block whose pairing rule we do
     * not know would attach one lock to two unrelated targets.
     */
    @Nullable
    public static BlockPos partner(@Nullable BlockGetter level, @Nullable BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockState state;
        try {
            state = level.getBlockState(pos);
        } catch (RuntimeException unloaded) {
            return null;
        }
        return partner(pos, state);
    }

    /** The state-driven half of {@link #partner}, separated so a caller with a state can reuse it. */
    @Nullable
    public static BlockPos partner(@Nullable BlockPos pos, @Nullable BlockState state) {
        if (pos == null || state == null) {
            return null;
        }
        if (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.HALF)) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                    ? pos.below().immutable()
                    : pos.above().immutable();
        }
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction connected = ChestBlock.getConnectedDirection(state);
            return connected == null ? null : pos.relative(connected).immutable();
        }
        return null;
    }

    /** The canonical target for whatever is at {@code pos}, halves resolved. */
    public static LockTarget forBlock(@Nullable BlockGetter level, ResourceLocation dimension,
                                      BlockPos pos) {
        return canonical(dimension, pos, partner(level, pos));
    }
}
