package dev.otectus.mcacrime.block;

import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.block.entity.CrimeBlockEntities;
import dev.otectus.mcacrime.block.entity.PilloryBlockEntity;
import dev.otectus.mcacrime.detention.DetentionKind;
import dev.otectus.mcacrime.detention.DetentionService;
import dev.otectus.mcacrime.restraint.RestraintService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

/**
 * A pillory: two blocks that hold one subject by the neck and wrists (0.7.5 M4.5).
 *
 * <p>Generalised to villagers, which the source cannot do because it keeps occupancy on the player
 * entity. Here the occupancy is a {@code detention/DetentionRecord} keyed by this device's lower half,
 * so anybody restrainable can be put in one and a chunk unload is not an escape.
 *
 * <p>The detention is <b>independent of the head slot</b>. The source models the pillory as a head
 * restraint ({@code restraints/custom/PilloryRestraint.java:31}), so putting somebody in one takes
 * their hood off; here a hood, a gag and a pillory coexist, which is both the obvious behaviour and
 * what lets the composite restriction profile stay the single authority.
 */
public class PilloryBlock extends BaseEntityBlock implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty CLOSED = BooleanProperty.create("closed");
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    /** How far off the device's axis the occupant stands. The source's figure. */
    public static final double STAND_OFFSET = 0.363D;

    /** How close the subject has to be to the standing spot to count as in the device. */
    public static final double STAND_TOLERANCE = 0.6D;

    private static final VoxelShape POSTS_NS = Shapes.or(
            Block.box(-1.0D, 0.0D, 6.0D, 2.0D, 16.0D, 10.0D),
            Block.box(14.0D, 0.0D, 6.0D, 17.0D, 16.0D, 10.0D));
    private static final VoxelShape POSTS_EW = Shapes.or(
            Block.box(6.0D, 0.0D, -1.0D, 10.0D, 16.0D, 2.0D),
            Block.box(6.0D, 0.0D, 14.0D, 10.0D, 16.0D, 17.0D));
    private static final VoxelShape BOARD_NS = Block.box(2.0D, 4.0D, 7.0D, 14.0D, 9.0D, 9.0D);
    private static final VoxelShape BOARD_EW = Block.box(7.0D, 4.0D, 2.0D, 9.0D, 9.0D, 14.0D);

    /** 1.21 requires every block to declare a codec; {@code simpleCodec} is the Properties-only form. */
    public static final MapCodec<PilloryBlock> CODEC = simpleCodec(PilloryBlock::new);

    public PilloryBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CLOSED, Boolean.FALSE)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, CLOSED);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // --- shape and placement ------------------------------------------------------------------------

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext ctx) {
        boolean northSouth = state.getValue(FACING).getAxis() == Direction.Axis.Z;
        VoxelShape posts = northSouth ? POSTS_NS : POSTS_EW;
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            return posts;
        }
        return Shapes.or(posts, northSouth ? BOARD_NS : BOARD_EW);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        if (pos.getY() >= ctx.getLevel().getMaxBuildHeight() - 1
                || !ctx.getLevel().getBlockState(pos.above()).canBeReplaced(ctx)) {
            return null;
        }
        return defaultBlockState()
                .setValue(FACING, ctx.getHorizontalDirection())
                .setValue(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState otherState,
                                  LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
            // The other half is what this one mirrors. A guillotine is an allowed upper neighbour:
            // it replaces the board rather than the device, and the base must not vanish under it.
            if (otherState.is(this) && otherState.getValue(HALF) != half) {
                return state.setValue(FACING, otherState.getValue(FACING))
                        .setValue(CLOSED, otherState.getValue(CLOSED));
            }
            return half == DoubleBlockHalf.LOWER ? Blocks.AIR.defaultBlockState() : state;
        }
        return half == DoubleBlockHalf.LOWER && direction == Direction.DOWN
                && !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            BlockPos below = pos.below();
            return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
        }
        return level.getBlockState(pos.below()).is(this);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // --- the device's own geometry ---------------------------------------------------------------------

    /** The canonical position of the device one half belongs to: always the lower half. */
    public static BlockPos devicePos(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos.immutable();
    }

    /**
     * Where the occupant stands: just behind the boards, on the ground.
     *
     * <p>Pure, and taken from the <em>lower</em> half so the guillotine and the pillory agree on one
     * spot however tall the device above it is.
     */
    public static Vec3 standingPosition(Direction facing, BlockPos devicePos) {
        double x = devicePos.getX() + 0.5D;
        double z = devicePos.getZ() + 0.5D;
        Direction behind = facing == null ? Direction.NORTH : facing;
        x += behind == Direction.EAST ? -STAND_OFFSET : behind == Direction.WEST ? STAND_OFFSET : 0.0D;
        z += behind == Direction.SOUTH ? -STAND_OFFSET : behind == Direction.NORTH ? STAND_OFFSET : 0.0D;
        return new Vec3(x, devicePos.getY(), z);
    }

    /**
     * Whether a subject at {@code subjectPos} is standing in the device.
     *
     * <p>Horizontal distance only, like the source: a subject half a block up a slab is still in the
     * pillory, and a subject on the roof is not.
     */
    public static boolean atStandingSpot(Vec3 standing, Vec3 subjectPos) {
        if (standing == null || subjectPos == null) {
            return false;
        }
        double dx = standing.x - subjectPos.x;
        double dz = standing.z - subjectPos.z;
        return dx * dx + dz * dz <= STAND_TOLERANCE * STAND_TOLERANCE;
    }

    /** The restrainable subject standing in this device right now, or null. */
    @Nullable
    public static LivingEntity subjectAt(Level level, BlockState state, BlockPos anyHalf) {
        BlockPos device = devicePos(state, anyHalf);
        Vec3 standing = standingPosition(state.getValue(FACING), device);
        var box = new net.minecraft.world.phys.AABB(standing.x - 1.0D, standing.y - 1.0D, standing.z - 1.0D,
                standing.x + 1.0D, standing.y + 2.0D, standing.z + 1.0D);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                RestraintService::restrainable)) {
            if (!atStandingSpot(standing, candidate.position())) {
                continue;
            }
            double distance = candidate.position().distanceToSqr(standing);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    // --- interaction ------------------------------------------------------------------------------------

    /**
     * 1.21.1 splits the old {@code use} in two; the pillory is operated with an empty hand or with
     * anything held, so the item form delegates here rather than duplicating the toggle.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player.isCrouching()) {
            return InteractionResult.PASS; // crouching is the ordinary "place a block against it"
        }
        BlockPos device = devicePos(state, pos);
        BlockState deviceState = level.getBlockState(device);
        if (!deviceState.is(this)) {
            return InteractionResult.PASS;
        }
        return toggle(level, device, deviceState, player) ? InteractionResult.CONSUME
                : InteractionResult.PASS;
    }

    /**
     * Opens or closes the device, claiming or releasing occupancy atomically.
     *
     * <p>The state change follows the claim rather than leading it. Two players closing one pillory in
     * the same tick both reach here; the claim succeeds for exactly one of them, and the boards close
     * once.
     */
    public boolean toggle(Level level, BlockPos device, BlockState state, @Nullable Player actor) {
        CrimeWorldData data = data(level);
        if (data == null) {
            return false;
        }
        var dimension = level.dimension().location();
        var existing = DetentionService.at(data, dimension, device);
        if (existing.isPresent()) {
            DetentionService.release(level.getServer(), data, existing.get().id(),
                    DetentionService.ReleaseReason.OPENED);
            dev.otectus.mcacrime.restraint.CustodyTransitionService.onDetentionEnded(
                    level.getServer(), existing.get(), DetentionService.ReleaseReason.OPENED, actor);
            level.setBlock(device, state.setValue(CLOSED, Boolean.FALSE), Block.UPDATE_ALL);
            setUpperClosed(level, device, false);
            CrimeSounds.pilloryUsed(level, device, false);
            return true;
        }
        LivingEntity subject = subjectAt(level, state, device);
        if (subject == null) {
            return false; // nobody to hold; the boards stay as they are rather than clacking at air
        }
        DetentionKind kind = kindAbove(level, device);
        DetentionService.Refusal refusal = DetentionService.claim(data, subject, kind, dimension,
                device, kind.id());
        if (refusal != DetentionService.Refusal.NONE) {
            if (actor != null && refusal != DetentionService.Refusal.NO_SUBJECT) {
                actor.displayClientMessage(
                        Component.translatable(DetentionService.messageKey(refusal)), true);
            }
            return false;
        }
        dev.otectus.mcacrime.restraint.CustodyTransitionService.onDetentionBegan(level.getServer(),
                subject, kind, actor);
        level.setBlock(device, state.setValue(CLOSED, Boolean.TRUE), Block.UPDATE_ALL);
        setUpperClosed(level, device, true);
        CrimeSounds.pilloryUsed(level, device, true);
        return true;
    }

    /** Whether the device at this position is capped by a guillotine rather than by boards. */
    public static DetentionKind kindAbove(Level level, BlockPos device) {
        return level.getBlockState(device.above(2)).getBlock() instanceof GuillotineBlock
                ? DetentionKind.GUILLOTINE : DetentionKind.PILLORY;
    }

    private void setUpperClosed(Level level, BlockPos device, boolean closed) {
        BlockPos upper = device.above();
        BlockState state = level.getBlockState(upper);
        if (state.is(this) && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            level.setBlock(upper, state.setValue(CLOSED, closed), Block.UPDATE_ALL);
        }
    }

    // --- destruction ---------------------------------------------------------------------------------------

    /**
     * Either half being broken releases the occupant exactly once.
     *
     * <p>Outside any breakout toggle, which is the fix for the source's coupling: a server that has
     * turned breaking out off has not turned off the release of somebody whose device was destroyed
     * around them.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            BlockPos device = devicePos(state, pos);
            CrimeWorldData data = data(level);
            DetentionService.releaseAt(level.getServer(), data, level.dimension().location(), device,
                    DetentionService.ReleaseReason.DEVICE_GONE)
                    .ifPresent(record -> dev.otectus.mcacrime.restraint.CustodyTransitionService
                            .onDetentionEnded(level.getServer(), record,
                                    DetentionService.ReleaseReason.DEVICE_GONE, null));
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.isCreative()
                && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            BlockState lower = level.getBlockState(below);
            if (lower.is(this) && lower.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(player, 2001, below, Block.getId(lower));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // --- block entity -------------------------------------------------------------------------------------

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Only the lower half carries one: it is the device, and two tickers on one device would
        // count every crouch transition twice.
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new PilloryBlockEntity(pos, state) : null;
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide() || state.getValue(HALF) != DoubleBlockHalf.LOWER) {
            return null;
        }
        return createTickerHelper(type, CrimeBlockEntities.PILLORY.get(), PilloryBlockEntity::serverTick);
    }

    /** The world store behind a level, or null on a client level. */
    @Nullable
    public static CrimeWorldData data(Level level) {
        return level instanceof net.minecraft.server.level.ServerLevel server
                ? CrimeWorldData.get(server.getServer()) : null;
    }
}
