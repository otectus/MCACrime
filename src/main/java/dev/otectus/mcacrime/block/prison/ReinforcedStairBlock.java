package dev.otectus.mcacrime.block.prison;

import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

/** The reinforced stair (M5.4). Vanilla stair behaviour; reinforced piston reaction. */
public class ReinforcedStairBlock extends StairBlock implements ReinforcedBlockBehaviour {

    public ReinforcedStairBlock(Supplier<BlockState> base, Properties properties) {
        super(base, properties);
    }
}
