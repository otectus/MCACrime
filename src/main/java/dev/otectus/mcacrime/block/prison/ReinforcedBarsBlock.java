package dev.otectus.mcacrime.block.prison;

import dev.otectus.mcacrime.block.CellDoorBlock;
import dev.otectus.mcacrime.block.CrimeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

import javax.annotation.Nonnull;

/**
 * Cell bars that know where they sit in a column (M5.4).
 *
 * <p>{@code column} is 0 for a free-standing or top section, 1 for a middle and 2 for a bottom, and
 * it exists so the three-part bar texture lines up down a wall instead of repeating the same cap
 * every metre. It is derived, never placed by hand.
 *
 * <p>The upstream version computes {@code up} partly from the block <em>below</em> — the gapped-bars
 * term in its "is there something above me" test reads {@code pos.below()} in both branches
 * ({@code ReinforcedBarsBlock.java:41-47}) — so a gapped section under a plain one silently picks the
 * wrong column. Both directions are read from their own neighbour here.
 */
public class ReinforcedBarsBlock extends IronBarsBlock implements ReinforcedBlockBehaviour {

    /** 0 = single or top, 1 = middle, 2 = bottom. */
    public static final IntegerProperty COLUMN = IntegerProperty.create("column", 0, 2);

    public ReinforcedBarsBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, Boolean.FALSE)
                .setValue(EAST, Boolean.FALSE)
                .setValue(SOUTH, Boolean.FALSE)
                .setValue(WEST, Boolean.FALSE)
                .setValue(WATERLOGGED, Boolean.FALSE)
                .setValue(COLUMN, 0));
    }

    @Override
    protected void createBlockStateDefinition(@Nonnull StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, WEST, SOUTH, WATERLOGGED, COLUMN);
    }

    /** Whether the block at {@code pos} continues a run of bars. */
    private static boolean continues(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(CrimeBlocks.REINFORCED_BARS.get())
                || state.is(CrimeBlocks.REINFORCED_BARS_GAP.get())
                || state.is(CrimeBlocks.CELL_DOOR.get());
    }

    @Override
    public BlockState getStateForPlacement(@Nonnull BlockPlaceContext ctx) {
        BlockState placed = super.getStateForPlacement(ctx);
        if (placed == null) {
            return null;
        }
        BlockPos pos = ctx.getClickedPos();
        BlockState joined = placed.setValue(COLUMN,
                ReinforcedBarsShape.column(continues(ctx.getLevel(), pos.above()), continues(ctx.getLevel(), pos.below())));
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (joinsPanel(ctx.getLevel().getBlockState(pos.relative(side)), side)) {
                joined = joined.setValue(PROPERTY_BY_DIRECTION.get(side), Boolean.TRUE);
            }
        }
        return joined;
    }

    /**
     * Whether an arm should reach toward {@code neighbour} where vanilla's own rule would leave a post.
     *
     * <p>{@code IronBarsBlock.attachsTo} is final and joins only other bars, walls and sturdy faces.
     * A cell door hung in the bars is thin, centred and never sturdy, and the gapped window is not a
     * pane, so both stood in their run with a bare post on either side and a half-block gap to it. Each
     * is a flat panel, so an arm joins it along its plane and never through its face, and a cell door
     * only while it is drawn in its barred, centred form: the plain door sits at the block's edge,
     * where an arm along the middle would miss it.
     */
    static boolean joinsPanel(BlockState neighbour, Direction toward) {
        if (neighbour.getBlock() instanceof CellDoorBlock && neighbour.getValue(CellDoorBlock.IN_BARS)) {
            return ReinforcedBarsShape.joinsPanel(neighbour.getValue(DoorBlock.FACING).getAxis(), toward);
        }
        if (neighbour.getBlock() instanceof ReinforcedBarsGappedBlock) {
            return ReinforcedBarsShape.joinsPanel(
                    neighbour.getValue(ReinforcedBarsGappedBlock.FACING).getAxis(), toward);
        }
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(@Nonnull BlockState state, @Nonnull Direction direction,
                                  @Nonnull BlockState neighbour, @Nonnull LevelAccessor level,
                                  @Nonnull BlockPos pos, @Nonnull BlockPos neighbourPos) {
        BlockState recoloured = state;
        if (direction.getAxis() == Direction.Axis.Y) {
            recoloured = state.setValue(COLUMN,
                    ReinforcedBarsShape.column(continues(level, pos.above()), continues(level, pos.below())));
        }
        BlockState updated = super.updateShape(recoloured, direction, neighbour, level, pos, neighbourPos);
        if (direction.getAxis().isHorizontal() && joinsPanel(neighbour, direction)) {
            updated = updated.setValue(PROPERTY_BY_DIRECTION.get(direction), Boolean.TRUE);
        }
        return updated;
    }
}
