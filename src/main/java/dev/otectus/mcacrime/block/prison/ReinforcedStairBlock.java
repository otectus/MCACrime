package dev.otectus.mcacrime.block.prison;

import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The reinforced stair (M5.4). Vanilla stair behaviour; reinforced piston reaction.
 *
 * <p>1.21.1 takes the base state directly rather than the baseline's {@code Supplier<BlockState>}
 * (Forge's patched constructor is gone; vanilla's is {@code StairBlock(BlockState, Properties)}), so
 * the caller resolves the base block inside the registration lambda, where it is already registered.
 */
public class ReinforcedStairBlock extends StairBlock implements ReinforcedBlockBehaviour {

    public ReinforcedStairBlock(BlockState base, Properties properties) {
        super(base, properties);
    }
}
