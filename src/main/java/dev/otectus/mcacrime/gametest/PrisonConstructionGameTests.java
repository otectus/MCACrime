package dev.otectus.mcacrime.gametest;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.block.prison.ReinforcedPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The prison construction set, standing in a world (plan section 7.2, M5.4).
 *
 * <p>One thing a unit test cannot answer: whether {@code mcacrime:reinforced_blocks} actually
 * resolves depends on the shipped datapack being loaded, not on the tag file existing.
 *
 * <p>Nothing here writes to the config. The breaking policy is read at its shipped default, so this
 * test shares the default batch with everything else.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PrisonConstructionGameTests {

    private PrisonConstructionGameTests() {
    }

    /** The eight blocks the M5.4 set registers, in registration order. */
    private static List<Block> reinforcedSet() {
        return List.of(CrimeBlocks.REINFORCED_STONE.get(), CrimeBlocks.REINFORCED_SMOOTH_STONE.get(),
                CrimeBlocks.CHISELED_REINFORCED_STONE.get(), CrimeBlocks.REINFORCED_LAMP.get(),
                CrimeBlocks.REINFORCED_STONE_SLAB.get(), CrimeBlocks.REINFORCED_STONE_STAIRS.get(),
                CrimeBlocks.REINFORCED_BARS.get(), CrimeBlocks.REINFORCED_BARS_GAP.get());
    }

    /**
     * Every reinforced block is in the tag, needs the right tool, and is immovable by a piston.
     *
     * <p>The tag is the one thing the breaking policy reads, so a
     * block that registered but never reached it is a wall with none of the rules on it. The piston
     * reaction comes from the block extension rather than from the properties, which is a hook only a
     * loaded game exercises.
     */
    @GameTest(template = "platform", timeoutTicks = 200)
    public static void theReinforcedSetIsTaggedQualifiedAndImmovable(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        for (Block block : reinforcedSet()) {
            helper.setBlock(at, block);
            BlockState state = helper.getBlockState(at);
            helper.assertTrue(ReinforcedPolicy.isReinforced(state),
                    block + " is not in mcacrime:reinforced_blocks, so no prison rule applies to it");
            helper.assertTrue(state.requiresCorrectToolForDrops(),
                    block + " drops without the qualified tool the documentation promises");
            helper.assertTrue(state.getPistonPushReaction() == PushReaction.BLOCK,
                    block + " can be pushed by a piston, so a wall could be walked away from");
        }
        // And an ordinary stone wall is not in the set: the rules are about this material, not about
        // walls in general.
        helper.setBlock(at, net.minecraft.world.level.block.Blocks.STONE);
        helper.assertTrue(!ReinforcedPolicy.isReinforced(helper.getBlockState(at)),
                "plain stone answered the reinforced tag");
        helper.succeed();
    }

}
