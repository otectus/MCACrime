package dev.otectus.mcacrime.block.entity;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * This mod's block entity types (0.7.5 M3.4, M3.5, M4.5-M4.7): the cell door's lock handle, the
 * safe and the three detention devices.
 *
 * <p>Registered after {@code CrimeBlocks}, because a {@code BlockEntityType} names the blocks it is
 * valid for and resolving one before its block exists is a null in a registry callback rather than an
 * error anybody can read.
 */
public final class CrimeBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, McaCrime.MOD_ID);

    /** The cell door's lower half. Stores the lock id and nothing else. */
    public static final RegistryObject<BlockEntityType<LockableBlockEntity>> CELL_DOOR =
            BLOCK_ENTITIES.register("cell_door", () -> BlockEntityType.Builder
                    .of(LockableBlockEntity::new, CrimeBlocks.CELL_DOOR.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<SafeBlockEntity>> SAFE =
            BLOCK_ENTITIES.register("safe", () -> BlockEntityType.Builder
                    .of(SafeBlockEntity::new, CrimeBlocks.SAFE.get())
                    .build(null));

    /** The pillory's lower half: the device's tick and its crouch-transition counter (M4.5). */
    public static final RegistryObject<BlockEntityType<PilloryBlockEntity>> PILLORY =
            BLOCK_ENTITIES.register("pillory", () -> BlockEntityType.Builder
                    .of(PilloryBlockEntity::new, CrimeBlocks.PILLORY.get())
                    .build(null));

    /** The guillotine: the persisted blade delay and completion marker (M4.6). */
    public static final RegistryObject<BlockEntityType<GuillotineBlockEntity>> GUILLOTINE =
            BLOCK_ENTITIES.register("guillotine", () -> BlockEntityType.Builder
                    .of(GuillotineBlockEntity::new, CrimeBlocks.GUILLOTINE.get())
                    .build(null));

    /** The bunk's head half: who is sleeping in it (M4.7). */
    public static final RegistryObject<BlockEntityType<BunkBlockEntity>> BUNK =
            BLOCK_ENTITIES.register("bunk", () -> BlockEntityType.Builder
                    .of(BunkBlockEntity::new, CrimeBlocks.BUNK.get())
                    .build(null));

    private CrimeBlockEntities() {
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
