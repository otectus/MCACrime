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
 * This mod's blocks: the Mask Station (0.7.2), the lockable and detention devices (0.7.5 M3, M4) and
 * the reinforced construction set (0.7.5 M5.4).
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

    /**
     * A barred iron door that can carry a lock (M3.4).
     *
     * <p>Iron-door hardness and an iron block set, so it sounds and mines like the thing it is. No
     * redstone behaviour at all — see {@link CellDoorBlock#neighborChanged}.
     */
    public static final RegistryObject<Block> CELL_DOOR = BLOCKS.register("cell_door",
            () -> new CellDoorBlock(BlockBehaviour.Properties.of()
                    .strength(5.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK),
                    net.minecraft.world.level.block.state.properties.BlockSetType.IRON));

    /**
     * A lockable strongbox (M3.5).
     *
     * <p>Tougher than a chest and immovable by pistons: a container whose protection a piston can walk
     * away from is not protected.
     */
    public static final RegistryObject<Block> SAFE = BLOCKS.register("safe",
            () -> new SafeBlock(BlockBehaviour.Properties.of()
                    .strength(6.0F, 1200.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));

    /**
     * The pillory (M4.5). Wood-and-iron: a workbench's hardness, axe-mined, immovable by pistons --
     * a device a piston could walk away from would take its occupant with it.
     */
    public static final RegistryObject<Block> PILLORY = BLOCKS.register("pillory",
            () -> new PilloryBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(2.5F)
                    .sound(SoundType.WOOD)
                    .pushReaction(PushReaction.BLOCK)));

    /** The guillotine's blade frame (M4.6). Harder than the pillory it caps, and metal-shod. */
    public static final RegistryObject<Block> GUILLOTINE = BLOCKS.register("guillotine",
            () -> new GuillotineBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(3.5F)
                    .sound(SoundType.WOOD)
                    .pushReaction(PushReaction.BLOCK)));

    /** The prison bunk (M4.7). Reinforced: a bed somebody else built you, and harder to take apart. */
    public static final RegistryObject<Block> BUNK = BLOCKS.register("bunk",
            () -> new BunkBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(2.0F)
                    .sound(SoundType.METAL)
                    .pushReaction(PushReaction.BLOCK)));

    // --- the reinforced construction set (M5.4) --------------------------------------------------

    /**
     * The base properties every reinforced masonry block copies: stone sounds, an iron-pickaxe
     * requirement, and obsidian-adjacent blast resistance so a creeper is an inconvenience rather than
     * a jailbreak. {@code requiresCorrectToolForDrops} is what "pickaxe qualified" means in practice.
     *
     * <p>A method rather than a shared constant because {@code BlockBehaviour.Properties} is mutable:
     * handing the same instance to eight registrations would let the last one's {@code lightLevel}
     * reach all eight.
     */
    private static BlockBehaviour.Properties reinforcedMasonry() {
        return BlockBehaviour.Properties.of()
                .strength(6.0F, 1200.0F)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops();
    }

    public static final RegistryObject<Block> REINFORCED_STONE = BLOCKS.register("reinforced_stone",
            () -> new dev.otectus.mcacrime.block.prison.ReinforcedBlock(reinforcedMasonry()));

    public static final RegistryObject<Block> REINFORCED_SMOOTH_STONE =
            BLOCKS.register("reinforced_smooth_stone",
                    () -> new dev.otectus.mcacrime.block.prison.ReinforcedBlock(reinforcedMasonry()));

    public static final RegistryObject<Block> CHISELED_REINFORCED_STONE =
            BLOCKS.register("chiseled_reinforced_stone",
                    () -> new dev.otectus.mcacrime.block.prison.ReinforcedBlock(reinforcedMasonry()));

    /** The cell light. Glass sounds and a full light level; still iron-pickaxe masonry underneath. */
    public static final RegistryObject<Block> REINFORCED_LAMP = BLOCKS.register("reinforced_lamp",
            () -> new dev.otectus.mcacrime.block.prison.ReinforcedBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.0F, 8.0F)
                            .sound(SoundType.GLASS)
                            .requiresCorrectToolForDrops()
                            .lightLevel(state -> 15)));

    public static final RegistryObject<Block> REINFORCED_STONE_SLAB =
            BLOCKS.register("reinforced_stone_slab",
                    () -> new dev.otectus.mcacrime.block.prison.ReinforcedSlabBlock(reinforcedMasonry()));

    public static final RegistryObject<Block> REINFORCED_STONE_STAIRS =
            BLOCKS.register("reinforced_stone_stairs",
                    () -> new dev.otectus.mcacrime.block.prison.ReinforcedStairBlock(
                            () -> REINFORCED_STONE.get().defaultBlockState(), reinforcedMasonry()));

    /** Cell bars. Metal, no occlusion, and the same iron-pickaxe requirement as the walls. */
    public static final RegistryObject<Block> REINFORCED_BARS = BLOCKS.register("reinforced_bars",
            () -> new dev.otectus.mcacrime.block.prison.ReinforcedBarsBlock(
                    BlockBehaviour.Properties.of()
                            .strength(6.0F, 1200.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()));

    /** The barred window: bars with a gap, kept out of the connection set so the gap stays open. */
    public static final RegistryObject<Block> REINFORCED_BARS_GAP = BLOCKS.register("reinforced_bars_gap",
            () -> new dev.otectus.mcacrime.block.prison.ReinforcedBarsGappedBlock(
                    BlockBehaviour.Properties.of()
                            .strength(6.0F, 1200.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()));

    private CrimeBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
