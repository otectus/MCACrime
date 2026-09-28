package dev.otectus.mcacrime.block.prison;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.common.extensions.IBlockExtension;

import org.jetbrains.annotations.Nullable;

/**
 * The one behaviour every reinforced block shares: a piston cannot move it while the server says so.
 *
 * <p>An interface rather than a common superclass, because the set spans {@code Block},
 * {@code SlabBlock}, {@code StairBlock} and {@code IronBarsBlock} — four different vanilla parents.
 * NeoForge's {@code IBlockExtension#getPistonPushReaction} returns null to mean "use the value the
 * block's properties carry", which is exactly the fallback wanted when the setting is off.
 *
 * <p>Read live rather than baked into the block properties on purpose: a server that turns piston
 * immunity off should see it take effect on the next config reload, not the next world.
 */
public interface ReinforcedBlockBehaviour extends IBlockExtension {

    @Nullable
    @Override
    default PushReaction getPistonPushReaction(BlockState state) {
        return ReinforcedPolicy.resistsPistons() ? PushReaction.BLOCK : null;
    }
}
