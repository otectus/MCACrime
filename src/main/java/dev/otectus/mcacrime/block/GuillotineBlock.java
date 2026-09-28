package dev.otectus.mcacrime.block;

import dev.otectus.mcacrime.block.entity.CrimeBlockEntities;
import dev.otectus.mcacrime.block.entity.GuillotineBlockEntity;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

/**
 * The blade frame that caps a pillory (0.7.5 M4.6).
 *
 * <p>It is the third block of a three-block device: a pillory's lower half holds the occupant, its
 * upper half holds their neck, and this sits on top. Placing one anywhere else is refused, so a
 * guillotine can never exist with nothing under it to hold somebody — which is the state in which
 * "who is in this device?" has no answer.
 *
 * <p>The block decides nothing about life and death. It toggles a blade, and the block entity asks
 * {@link ExecutionAuthorization} whether this device may act. With no live authorisation the blade
 * falls, the device opens, and the occupant walks away unharmed.
 */
public class GuillotineBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Whether the blade is down. The visual half of the activation; the delay is the authority. */
    public static final BooleanProperty BLADE_DOWN = BooleanProperty.create("blade_down");
    /** Whether the device has been used. Cosmetic, and persisted with the block entity too. */
    public static final BooleanProperty BLOODY = BooleanProperty.create("bloody");

    private static final VoxelShape FRAME_NS = Shapes.or(
            Block.box(-1.0D, 0.0D, 6.0D, 2.0D, 16.0D, 10.0D),
            Block.box(14.0D, 0.0D, 6.0D, 17.0D, 16.0D, 10.0D),
            Block.box(-1.0D, 13.0D, 6.0D, 17.0D, 16.0D, 10.0D));
    private static final VoxelShape FRAME_EW = Shapes.or(
            Block.box(6.0D, 0.0D, -1.0D, 10.0D, 16.0D, 2.0D),
            Block.box(6.0D, 0.0D, 14.0D, 10.0D, 16.0D, 17.0D),
            Block.box(6.0D, 13.0D, -1.0D, 10.0D, 16.0D, 17.0D));

    /** 1.21 requires every block to declare a codec; {@code simpleCodec} is the Properties-only form. */
    public static final MapCodec<GuillotineBlock> CODEC = simpleCodec(GuillotineBlock::new);

    public GuillotineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(BLADE_DOWN, Boolean.FALSE)
                .setValue(BLOODY, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, BLADE_DOWN, BLOODY);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext ctx) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? FRAME_NS : FRAME_EW;
    }

    // --- placement -----------------------------------------------------------------------------------

    /** The pillory lower half two blocks down, when this guillotine caps a real device. */
    public static BlockPos devicePos(BlockPos guillotine) {
        return guillotine.below(2);
    }

    /** Whether a guillotine may stand here: on the upper half of a pillory, and nowhere else. */
    public static boolean supported(LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.getBlock() instanceof PilloryBlock
                && below.getValue(PilloryBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        if (!supported(ctx.getLevel(), ctx.getClickedPos())) {
            return null;
        }
        BlockState pillory = ctx.getLevel().getBlockState(ctx.getClickedPos().below());
        // The facing comes from the device it caps, never from where the placer is standing: a blade
        // turned ninety degrees from the neck under it would be a device that cannot work.
        return defaultBlockState().setValue(FACING, pillory.getValue(PilloryBlock.FACING));
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return supported(level, pos);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState otherState,
                                  net.minecraft.world.level.LevelAccessor level, BlockPos pos,
                                  BlockPos otherPos) {
        return direction == Direction.DOWN && !canSurvive(state, level, pos)
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // --- interaction ----------------------------------------------------------------------------------

    /** 1.21.1 splits the old {@code use}; the lever is pulled with whatever is in hand or nothing. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player.isCrouching()) {
            return InteractionResult.PASS;
        }
        if (!(level.getBlockEntity(pos) instanceof GuillotineBlockEntity device)) {
            return InteractionResult.PASS;
        }
        device.pullLever(level, pos, state, player);
        return InteractionResult.CONSUME;
    }

    // --- destruction -----------------------------------------------------------------------------------

    /**
     * Breaking the frame clears any order armed at this device.
     *
     * <p>One of §3.19's seven clearing rules, and the reason they all live in one place: the
     * condemned go back to being condemned in custody, not to being free and not to being dead.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            ExecutionAuthorization.clearAt(level.dimension().location(), devicePos(pos),
                    ExecutionAuthorization.ClearReason.DEVICE_DESTROYED);
            if (level instanceof net.minecraft.server.level.ServerLevel server) {
                // The device index drops it too (M6.7). Stale entries are verified before they are
                // handed out, so this is tidiness rather than correctness -- but a guard walking a
                // prisoner to a device that is not there is a wasted trip nobody should have to take.
                dev.otectus.mcacrime.enforcement.ExecutionSiteRegistry.forget(server, devicePos(pos));
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    /**
     * A placed guillotine is remembered, so finding one later is a map read (M6.7).
     *
     * <p>The alternative is the world scan upstream does on every right-click. The index is
     * memory-only and an empty one is corrected by one bounded look around an assigned site, so a
     * restart costs a single search and never a wrong answer.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState replaced,
                           boolean moved) {
        super.onPlace(state, level, pos, replaced, moved);
        if (!level.isClientSide() && level instanceof net.minecraft.server.level.ServerLevel server) {
            dev.otectus.mcacrime.enforcement.ExecutionSiteRegistry.remember(server, devicePos(pos));
        }
    }

    // --- block entity ------------------------------------------------------------------------------------

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GuillotineBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        return level.isClientSide() ? null
                : createTickerHelper(type, CrimeBlockEntities.GUILLOTINE.get(),
                        GuillotineBlockEntity::serverTick);
    }
}
