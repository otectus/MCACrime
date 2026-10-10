package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CellDoorBlock;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.block.entity.LockableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Every cell door combination placed in a real level (0.7.7).
 *
 * <p>Added while a modpack startup crash naming the cell door was investigated with no crash report:
 * the static audit found every state, model and texture valid, and {@code CellDoorBlockstateCoverageTest}
 * keeps the resource side honest. This keeps the world side honest: each facing, hinge, open and barred
 * combination is placed as a real two-block door, keeps the state it was given, answers its shape and
 * collision queries, and carries exactly the block entity it should. The lower half holds the lock's
 * block entity and the upper half none, which is the one hazard the audit noted and could not confirm;
 * this asserts what the world actually does with it.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CellDoorPlacementGameTests {

    private static final BlockPos LOWER = new BlockPos(6, 1, 6);

    private CellDoorPlacementGameTests() {
    }

    @GameTest(template = "cell_parity")
    public static void everyFacingHingeOpenAndBarredDoorPlacesAndBehaves(GameTestHelper helper) {
        var level = helper.getLevel();
        CellDoorBlock door = CrimeBlocks.CELL_DOOR.get();
        int placed = 0;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (DoorHingeSide hinge : DoorHingeSide.values()) {
                for (boolean open : new boolean[] {false, true}) {
                    for (boolean barred : new boolean[] {false, true}) {
                        String combo = facing + "/" + hinge + "/open=" + open + "/bars=" + barred;
                        clear(helper);
                        Direction left = facing.getAxis() == Direction.Axis.Z ? Direction.WEST : Direction.NORTH;
                        if (barred) {
                            for (BlockPos half : new BlockPos[] {LOWER, LOWER.above()}) {
                                helper.setBlock(half.relative(left), Blocks.IRON_BARS);
                                helper.setBlock(half.relative(left.getOpposite()), Blocks.IRON_BARS);
                            }
                        }
                        BlockState lowerState = door.defaultBlockState().setValue(DoorBlock.FACING, facing)
                                .setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.OPEN, open)
                                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(CellDoorBlock.IN_BARS, barred);
                        helper.setBlock(LOWER, lowerState);
                        helper.setBlock(LOWER.above(), lowerState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
                        BlockPos lower = helper.absolutePos(LOWER);
                        BlockPos upper = lower.above();
                        for (BlockPos pos : new BlockPos[] {lower, upper}) {
                            BlockState state = level.getBlockState(pos);
                            helper.assertTrue(state.is(door), combo + ": the door did not stay placed at " + pos);
                            helper.assertTrue(state.getValue(DoorBlock.FACING) == facing
                                            && state.getValue(DoorBlock.HINGE) == hinge
                                            && state.getValue(DoorBlock.OPEN) == open
                                            && state.getValue(CellDoorBlock.IN_BARS) == barred,
                                    combo + ": the placed door reads back as " + state);
                            helper.assertTrue(!state.getShape(level, pos, CollisionContext.empty()).isEmpty(),
                                    combo + ": empty outline shape");
                            state.getCollisionShape(level, pos, CollisionContext.empty());
                        }
                        helper.assertTrue(level.getBlockEntity(lower) instanceof LockableBlockEntity,
                                combo + ": the lower half carries no lock block entity");
                        helper.assertTrue(level.getBlockEntity(upper) == null,
                                combo + ": the upper half grew a block entity");
                        placed++;
                    }
                }
            }
        }
        clear(helper);
        helper.assertTrue(placed == 32, "expected 32 combinations, placed " + placed);
        helper.succeed();
    }

    private static void clear(GameTestHelper helper) {
        for (int x = 3; x <= 9; x++) {
            for (int z = 3; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }
}
