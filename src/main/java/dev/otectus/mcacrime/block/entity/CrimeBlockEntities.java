package dev.otectus.mcacrime.block.entity;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.block.CrimeBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * This mod's block entity types (0.7.5 M3.4, M3.5, M4.5-M4.7): the cell door's lock handle, the
 * safe and the three detention devices — the first block entities on this line at all.
 *
 * <p>Registered after {@code CrimeBlocks}, because a {@code BlockEntityType} names the blocks it is
 * valid for and resolving one before its block exists is a null in a registry callback rather than an
 * error anybody can read.
 *
 * <p>{@link #registerCapabilities} is the NeoForge half of the safe's automation protection. There is
 * no {@code getCapability} override here: a block capability is registered once, per block entity
 * type, and handed out by the provider below. The safe drops cached handlers with
 * {@code level.invalidateCapabilities(pos)} whenever its lock changes, which is this platform's
 * equivalent of the baseline's {@code LazyOptional.invalidate()} (plan §7.1 item 4).
 */
public final class CrimeBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, McaCrime.MOD_ID);

    /** The cell door's lower half. Stores the lock id and nothing else. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LockableBlockEntity>> CELL_DOOR =
            BLOCK_ENTITIES.register("cell_door", () -> BlockEntityType.Builder
                    .of(LockableBlockEntity::new, CrimeBlocks.CELL_DOOR.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SafeBlockEntity>> SAFE =
            BLOCK_ENTITIES.register("safe", () -> BlockEntityType.Builder
                    .of(SafeBlockEntity::new, CrimeBlocks.SAFE.get())
                    .build(null));

    /** The pillory's lower half: the device's tick and its crouch-transition counter (M4.5). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PilloryBlockEntity>> PILLORY =
            BLOCK_ENTITIES.register("pillory", () -> BlockEntityType.Builder
                    .of(PilloryBlockEntity::new, CrimeBlocks.PILLORY.get())
                    .build(null));

    /** The guillotine: the persisted blade delay and completion marker (M4.6). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GuillotineBlockEntity>> GUILLOTINE =
            BLOCK_ENTITIES.register("guillotine", () -> BlockEntityType.Builder
                    .of(GuillotineBlockEntity::new, CrimeBlocks.GUILLOTINE.get())
                    .build(null));

    /** The bunk's head half: who is sleeping in it (M4.7). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BunkBlockEntity>> BUNK =
            BLOCK_ENTITIES.register("bunk", () -> BlockEntityType.Builder
                    .of(BunkBlockEntity::new, CrimeBlocks.BUNK.get())
                    .build(null));

    private CrimeBlockEntities() {
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(CrimeBlockEntities::registerCapabilities);
    }

    /**
     * Hands out the safe's lock-aware item handler.
     *
     * <p>A new handler per request rather than a cached one, because the handler holds no decision:
     * every insert and extract asks the safe afresh. Caching would save an allocation and buy a stale
     * answer, which is the failure the specification names.
     */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, SAFE.get(),
                (safe, side) -> new LockAwareItemHandler(safe));
    }
}
