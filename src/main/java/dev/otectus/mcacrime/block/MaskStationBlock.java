package dev.otectus.mcacrime.block;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.menu.MaskStationMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

import javax.annotation.Nullable;

/**
 * The Mask Station (0.7.2 §6.1) — the Thief's workstation.
 *
 * <p>A horizontal-facing workbench. Spec §6.2 says the station is stateless and
 * §6.1 says no ticking block entity is required, so there is nothing here but orientation: the whole
 * point of the block is to be a point of interest that {@code mcacrime:mask_station} can claim, and
 * the crafting menu S3 will hang on it needs no per-block storage.
 *
 * <p>Piston-immovable, like every other villager workstation. A station a piston can shove one block
 * sideways would silently invalidate a claim the POI manager still holds, which is the same
 * unresolved-ownership problem as an unloaded chunk but caused on purpose by a redstone contraption.
 */
public class MaskStationBlock extends HorizontalDirectionalBlock {

    private static final VoxelShape BENCH = Shapes.or(
            box(0, 8, 0, 16, 10, 16), box(2, 2, 2, 14, 3, 14),
            box(1, 0, 1, 4, 8, 4), box(12, 0, 1, 15, 8, 4),
            box(1, 0, 12, 4, 8, 15), box(12, 0, 12, 15, 8, 15),
            box(4, 5, 1, 12, 8, 8));
    private static final VoxelShape NORTH_SHAPE = Shapes.or(BENCH,
            box(5, 10, 10, 11, 16, 14), box(2, 10, 11, 4, 14, 14),
            box(8, 10, 3, 13, 11, 5));
    private static final VoxelShape EAST_SHAPE = rotate(NORTH_SHAPE);
    private static final VoxelShape SOUTH_SHAPE = rotate(EAST_SHAPE);
    private static final VoxelShape WEST_SHAPE = rotate(SOUTH_SHAPE);

    private static VoxelShape rotate(VoxelShape shape) {
        VoxelShape[] rotated = {Shapes.empty()};
        shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> rotated[0] = Shapes.or(rotated[0],
                Shapes.box(1 - z2, y1, x1, 1 - z1, y2, x2)));
        return rotated[0].optimize();
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case EAST -> EAST_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return false;
    }

    public MaskStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Faces the player, like a lectern or a smithing table. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /**
     * Opens one private working session at this station (0.7.2 §6.2, §8.1).
     *
     * <p>Server-side only, and through {@code NetworkHooks.openScreen} with <em>this</em> position as
     * the extra data: the menu's authority comes from the position the server already knows, and the
     * client is merely told which block it is looking at. Nothing ever reads a position back off a
     * client packet, so no client can make the server reach into a chunk (§8.4).
     *
     * <p>With crafting disabled the block is inert rather than special-cased elsewhere — consuming the
     * interaction without opening anything would leave a player tapping a block that never answers.
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!McaCrimeConfig.COMMON.enableMaskStationCrafting.get()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.translatable("mcacrime.mask_station.title");
                }

                @Override
                public AbstractContainerMenu createMenu(int id, Inventory inventory, Player viewer) {
                    return new MaskStationMenu(id, inventory,
                            ContainerLevelAccess.create(level, pos), pos);
                }
            }, pos);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
