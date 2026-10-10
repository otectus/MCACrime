package dev.otectus.mcacrime.block.prison;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;

import java.util.List;

/** Plain reinforced stone and its chiselled, smooth and lamp variants (M5.4). */
public class ReinforcedBlock extends Block implements ReinforcedBlockBehaviour {

    public ReinforcedBlock(Properties properties) {
        super(properties);
    }

    /** A wall the mod built for a holding cell is never loot (0.7.7); everything else drops as usual. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return ReinforcedPolicy.generatedCellBlock(params) ? List.of() : super.getDrops(state, params);
    }
}
