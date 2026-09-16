package dev.otectus.mcacrime.block;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * This mod's blocks (0.7.2). One so far: the Mask Station.
 *
 * <p>Separate from {@code CrimeItems} because the block item has to be registered <em>after</em> the
 * block it wraps, and keeping the two registers in one class would hide that ordering behind field
 * declaration order in a file that is mostly about restraints.
 */
public final class CrimeBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, McaCrime.MOD_ID);

    /**
     * Matched to a vanilla wood workstation: 2.5 hardness, axe-mined, wood sounds (spec §6.1 asks for
     * "the project's normal wood-workstation hardness and tool behavior"; vanilla's fletching and
     * smithing tables are the convention this follows).
     */
    public static final RegistryObject<Block> MASK_STATION = BLOCKS.register("mask_station",
            () -> new MaskStationBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(2.5F)
                    .sound(SoundType.WOOD)
                    .pushReaction(PushReaction.BLOCK)));

    private CrimeBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
