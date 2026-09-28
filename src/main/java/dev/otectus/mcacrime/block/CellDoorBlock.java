package dev.otectus.mcacrime.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcacrime.block.entity.LockableBlockEntity;
import dev.otectus.mcacrime.locks.LockHolder;
import dev.otectus.mcacrime.locks.LockInteractions;
import dev.otectus.mcacrime.locks.LockProtection;
import dev.otectus.mcacrime.locks.LockRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

/**
 * The cell door: a barred iron door that can carry a lock (M3.4, ledger L05).
 *
 * <p>An ordinary door until somebody binds a key to it, which is why the lock row is created at the
 * first bind rather than at placement — a village full of cell doors nobody locked costs the
 * {@code locks} table nothing.
 *
 * <p>Two deliberate departures from a vanilla door. Redstone does nothing: a prison door that any
 * comparator can open is not a prison door, and upstream makes the same choice. And the lower half
 * carries the {@link LockableBlockEntity}, with both halves resolving to it through
 * {@code LockTargetNormalizer}, so a player clicking the top of a door is working the same lock as a
 * player clicking the bottom.
 *
 * <p>A padlock hung on the door holds it shut exactly as its own bound lock would: "is it locked" is
 * asked of the position, which both kinds of lock target, and the padlock answers key and pick
 * interactions on the door until a key is cut for the door itself. A padlock picked off a cell door
 * swings it open ({@code PadlockEntity.pickedOpen}).
 *
 * <p>1.21.1 note: the single 1.20.1 {@code use} is two methods here. A key, a ring, a pick or a bind
 * breaker arrives at {@link #useItemOn}; anything that is not about the lock falls through to
 * {@link #useWithoutItem}, which is the door's own behaviour. Every block also has to name a
 * {@link MapCodec}, and a door's needs its {@link BlockSetType} as well as its properties.
 */
public class CellDoorBlock extends DoorBlock implements EntityBlock {

    public static final MapCodec<CellDoorBlock> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BlockSetType.CODEC.fieldOf("block_set_type").forGetter(CellDoorBlock::type),
                    propertiesCodec())
                    .apply(instance, (setType, properties) -> new CellDoorBlock(properties, setType)));

    /** True when the door sits in a run of bars or walls, which swaps in the flat barred model. */
    public static final BooleanProperty IN_BARS = BooleanProperty.create("in_bars");

    private static final VoxelShape BARS_NS = Block.box(0.0D, 0.0D, 7.0D, 16.0D, 16.0D, 9.0D);
    private static final VoxelShape BARS_EW = Block.box(7.0D, 0.0D, 0.0D, 9.0D, 16.0D, 16.0D);

    public CellDoorBlock(Properties properties, BlockSetType setType) {
        super(setType, properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, Boolean.FALSE)
                .setValue(HINGE, DoorHingeSide.LEFT)
                .setValue(POWERED, Boolean.FALSE)
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(IN_BARS, Boolean.FALSE));
    }

    @Override
    public MapCodec<? extends DoorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, FACING, OPEN, HINGE, POWERED, IN_BARS);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext ctx) {
        if (state.getValue(IN_BARS) && !state.getValue(OPEN)) {
            Direction facing = state.getValue(FACING);
            return facing.getAxis() == Direction.Axis.Z ? BARS_NS : BARS_EW;
        }
        return super.getShape(state, getter, pos, ctx);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState placed = super.getStateForPlacement(ctx);
        if (placed == null) {
            return null;
        }
        return placed.setValue(IN_BARS,
                inBars(ctx.getLevel(), ctx.getClickedPos(), placed.getValue(FACING)));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState otherState,
                                     LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        BlockState updated = super.updateShape(state, direction, otherState, level, pos, otherPos);
        if (updated.is(this) && updated.hasProperty(IN_BARS)) {
            boolean bars = inBars(level, pos, updated.getValue(FACING));
            if (bars != updated.getValue(IN_BARS)) {
                return updated.setValue(IN_BARS, bars);
            }
        }
        return updated;
    }

    /** Whether both sides of this door are walls, bars or another cell door. */
    private static boolean inBars(LevelAccessor level, BlockPos pos, Direction facing) {
        Direction left = facing.getAxis() == Direction.Axis.Z ? Direction.WEST : Direction.NORTH;
        Direction right = left.getOpposite();
        return barLike(level, pos.relative(left)) && barLike(level, pos.relative(right));
    }

    private static boolean barLike(LevelAccessor level, BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            return state.is(BlockTags.WALLS) || state.getBlock() instanceof IronBarsBlock
                    || state.getBlock() instanceof CellDoorBlock;
        } catch (RuntimeException unloaded) {
            return false;
        }
    }

    /** Redstone never opens a cell door. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                   BlockPos fromPos, boolean moving) {
        // deliberately nothing
    }

    /** The lock half: a key, a ring, a master key, a bind breaker or a pick. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        LockableBlockEntity lockable = lockable(state, level, pos);
        if (lockable == null || !(player instanceof ServerPlayer server)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // The holder answering for this door: its own handle once a key has been cut for it, or the
        // padlock hanging on it until then. Either way the key and the pick reach the lock that is
        // actually holding the door, from whichever side the player is standing on.
        LockHolder holder = LockInteractions.holderAt(level, lowerHalf(state, pos));
        InteractionResult lockResult = LockInteractions.interact(server, level, stack,
                holder == null ? lockable : holder, lowerHalf(state, pos));
        return switch (lockResult) {
            case SUCCESS -> ItemInteractionResult.SUCCESS;
            case CONSUME -> ItemInteractionResult.CONSUME;
            case FAIL -> ItemInteractionResult.FAIL;
            default -> ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        };
    }

    /** The door half: locked doors refuse, unlocked ones swing. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        LockableBlockEntity lockable = lockable(state, level, pos);
        if (lockable == null) {
            return InteractionResult.PASS;
        }
        // Locked is decided by position, not by the handle's own id, so a padlock's lock -- which
        // targets this same canonical position -- holds the door it hangs on.
        LockRecord lock = LockProtection.lockAt(level, lowerHalf(state, pos)).orElse(null);
        if (lock != null && lock.locked()) {
            player.displayClientMessage(Component.translatable("mcacrime.lock.locked"), true);
            level.playSound(null, pos, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.4F, 1.4F);
            return InteractionResult.CONSUME;
        }
        BlockState cycled = state.cycle(OPEN);
        level.setBlock(pos, cycled, 10);
        level.playSound(null, pos, cycled.getValue(OPEN) ? SoundEvents.IRON_DOOR_OPEN
                : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F,
                level.getRandom().nextFloat() * 0.1F + 0.9F);
        level.gameEvent(player, cycled.getValue(OPEN) ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
        return InteractionResult.CONSUME;
    }

    private static BlockPos lowerHalf(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    @Nullable
    private static LockableBlockEntity lockable(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(lowerHalf(state, pos)) instanceof LockableBlockEntity lockable
                ? lockable : null;
    }

    /** Only the lower half carries the block entity: one lock handle per door, not two. */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER
                ? new LockableBlockEntity(pos, state)
                : null;
    }
}
