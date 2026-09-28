package dev.otectus.mcacrime.entity;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * This mod's entity types: the thrown Sand Bottle (0.7.2) and the padlock (0.7.5 M3.4).
 *
 * <p>The tracking values match vanilla's thrown items — a short range and a slow update interval,
 * because the client extrapolates the arc from its own physics and does not need a correction every
 * tick for something that exists for three seconds.
 */
public final class CrimeEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, McaCrime.MOD_ID);

    public static final RegistryObject<EntityType<SandBottleProjectile>> SAND_BOTTLE =
            ENTITIES.register("sand_bottle", () -> EntityType.Builder
                    .<SandBottleProjectile>of(SandBottleProjectile::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build("sand_bottle"));

    /**
     * A padlock hanging on a block (M3.4).
     *
     * <p>Tracked like a painting: a small fixed thing that never moves, so a long update interval
     * costs nothing. It updates on change rather than on a schedule, which is what the locked and
     * reinforced synched flags are for.
     */
    public static final RegistryObject<EntityType<PadlockEntity>> PADLOCK =
            ENTITIES.register("padlock", () -> EntityType.Builder
                    .<PadlockEntity>of(PadlockEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    .updateInterval(Integer.MAX_VALUE)
                    .build("padlock"));

    /**
     * The knot a chain is tied to on a fence or a tripwire hook (M4.2).
     *
     * <p>Tracked like the padlock: a small fixed thing that never moves on its own, so it updates on
     * change rather than on a schedule. The range is wider than the padlock's because a chain drawn
     * to it has to be visible from the far end of the chain.
     */
    public static final RegistryObject<EntityType<ChainKnotEntity>> CHAIN_KNOT =
            ENTITIES.register("chain_knot", () -> EntityType.Builder
                    .<ChainKnotEntity>of(ChainKnotEntity::new, MobCategory.MISC)
                    .sized(0.375F, 0.5F)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .build("chain_knot"));

    private CrimeEntities() {
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }
}
