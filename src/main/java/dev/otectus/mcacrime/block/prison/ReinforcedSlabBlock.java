package dev.otectus.mcacrime.block.prison;

import net.minecraft.world.level.block.SlabBlock;

/** The reinforced slab (M5.4). Vanilla slab behaviour; reinforced piston reaction. */
public class ReinforcedSlabBlock extends SlabBlock implements ReinforcedBlockBehaviour {

    public ReinforcedSlabBlock(Properties properties) {
        super(properties);
    }
}
