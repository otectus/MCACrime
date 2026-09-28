package dev.otectus.mcacrime.block;

import dev.otectus.mcacrime.block.entity.BunkBlockEntity;
import dev.otectus.mcacrime.detention.BunkRespawnPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * A prison bunk: a reinforced bed a prisoner may sleep in (0.7.5 M4.7).
 *
 * <p>Two parts and vanilla's own sleeping path, so night passes, phantoms stay away and every mod
 * that listens for a player going to bed hears it — none of which a bespoke "lie down" would give.
 * What it adds is the custody half: while {@code detention.bunkSetsRespawn} is on the sleeper's
 * respawn point moves here, with their previous one snapshotted, and
 * {@link BunkRespawnPolicy} gives it back on release <b>only if this system still owns the
 * override</b>.
 *
 * <p>Deliberately not a {@code BedBlock} subclass. A bed carries a dye colour, a bed block entity and
 * vanilla's explosion-in-the-Nether behaviour, and a prison bunk wants none of the three.
 */
public class BunkBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<BedPart> PART = BlockStateProperties.BED_PART;
    public static final BooleanProperty OCCUPIED = BlockStateProperties.OCCUPIED;

    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 9.0D, 16.0D);

    public BunkBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, BedPart.FOOT)
                .setValue(OCCUPIED, Boolean.FALSE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, OCCUPIED);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    // --- placement ------------------------------------------------------------------------------------

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection();
        BlockPos head = ctx.getClickedPos().relative(facing);
        return ctx.getLevel().getBlockState(head).canBeReplaced(ctx)
                && ctx.getLevel().getWorldBorder().isWithinBounds(head)
                ? defaultBlockState().setValue(FACING, facing)
                : null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        if (!level.isClientSide()) {
            level.setBlock(pos.relative(state.getValue(FACING)),
                    state.setValue(PART, BedPart.HEAD), Block.UPDATE_ALL);
            level.blockUpdated(pos, Blocks.AIR);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
    }

    /** The other half's direction from one part, which is what keeps the pair consistent. */
    private static Direction neighbourDirection(BedPart part, Direction facing) {
        return part == BedPart.FOOT ? facing : facing.getOpposite();
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState otherState,
                                  LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        if (direction == neighbourDirection(state.getValue(PART), state.getValue(FACING))) {
            return otherState.is(this) && otherState.getValue(PART) != state.getValue(PART)
                    ? state.setValue(OCCUPIED, otherState.getValue(OCCUPIED))
                    : Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** Breaking either half takes the other with it, without dropping a second bunk. */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.isCreative()) {
            BedPart part = state.getValue(PART);
            if (part == BedPart.FOOT) {
                BlockPos head = pos.relative(neighbourDirection(part, state.getValue(FACING)));
                BlockState headState = level.getBlockState(head);
                if (headState.is(this) && headState.getValue(PART) == BedPart.HEAD) {
                    level.setBlock(head, Blocks.AIR.defaultBlockState(), 35);
                    level.levelEvent(player, 2001, head, Block.getId(headState));
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    // --- sleeping ---------------------------------------------------------------------------------------

    /** The head half of this bunk, whichever half is given. */
    public static BlockPos headPos(BlockState state, BlockPos pos) {
        return state.getValue(PART) == BedPart.HEAD ? pos.immutable()
                : pos.relative(state.getValue(FACING));
    }

    /** Forge's sleeping hooks must classify this custom two-block bed as a bed. */
    @Override
    public boolean isBed(BlockState state, BlockGetter level, BlockPos pos, @Nullable Entity player) {
        return true;
    }

    @Override
    public Direction getBedDirection(BlockState state, LevelReader level, BlockPos pos) {
        return state.getValue(FACING);
    }

    /** Uses vanilla's safe stand-up search when this bunk is the player's respawn point. */
    @Override
    public Optional<Vec3> getRespawnPosition(BlockState state, EntityType<?> type,
                                             LevelReader levelReader, BlockPos pos,
                                             float orientation, @Nullable LivingEntity entity) {
        if (!(levelReader instanceof Level level) || !BedBlock.canSetSpawn(level)) {
            return Optional.empty();
        }
        BlockPos head = headPos(state, pos);
        return BedBlock.findStandUpPosition(type, levelReader, head, state.getValue(FACING),
                orientation);
    }

    /** Keeps both halves and the sleeper marker in step with vanilla's wake-up path. */
    @Override
    public void setBedOccupied(BlockState state, Level level, BlockPos pos, LivingEntity sleeper,
                               boolean occupied) {
        BlockPos head = headPos(state, pos);
        BlockState headState = level.getBlockState(head);
        if (headState.is(this)) {
            level.setBlock(head, headState.setValue(OCCUPIED, occupied), Block.UPDATE_ALL);
            if (!occupied && level.getBlockEntity(head) instanceof BunkBlockEntity bunk) {
                bunk.setSleeper(null);
            }
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos head = headPos(state, pos);
        BlockState headState = level.getBlockState(head);
        if (!headState.is(this)) {
            return InteractionResult.PASS;
        }
        if (headState.getValue(OCCUPIED)) {
            player.displayClientMessage(Component.translatable("mcacrime.bunk.occupied"), true);
            return InteractionResult.CONSUME;
        }
        return sleep(level, head, headState, player);
    }

    /**
     * Puts {@code player} to sleep, and takes the respawn snapshot if the sleep took.
     *
     * <p>Order matters: the snapshot is taken <em>before</em> the respawn point moves, and only after
     * vanilla has accepted the sleep. A refused sleep — monsters nearby, wrong time of day, too far
     * away — must leave the player's spawn exactly as it was.
     */
    private InteractionResult sleep(Level level, BlockPos head, BlockState state, Player player) {
        ServerPlayer sleeper = player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        // Settle an ended offline episode before capturing what this attempt is about to replace.
        BunkRespawnPolicy.reconcileOnLogin(sleeper);
        BunkRespawnPolicy.RespawnState before = BunkRespawnPolicy.current(sleeper);
        var outcome = player.startSleepInBed(head);
        if (outcome.left().isPresent()) {
            BunkRespawnPolicy.restoreImmediate(sleeper, before);
            Player.BedSleepingProblem problem = outcome.left().get();
            Component message = problem.getMessage();
            if (message != null) {
                player.displayClientMessage(message, true);
            }
            return InteractionResult.CONSUME;
        }
        if (!BunkRespawnPolicy.setsRespawn()) {
            BunkRespawnPolicy.restoreImmediate(sleeper, before);
        } else if (sleeper != null) {
            BunkRespawnPolicy.take(sleeper, head, before);
        }
        if (level.getBlockEntity(head) instanceof BunkBlockEntity bunk) {
            bunk.setSleeper(player.getUUID());
        }
        return InteractionResult.CONSUME;
    }

    // --- block entity -------------------------------------------------------------------------------------

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == BedPart.HEAD ? new BunkBlockEntity(pos, state) : null;
    }

    /** Whether this block entity type belongs to a bunk head; used by the sweep. */
    public static boolean isBunkHead(BlockState state) {
        return state.getBlock() instanceof BunkBlock && state.getValue(PART) == BedPart.HEAD;
    }
}
