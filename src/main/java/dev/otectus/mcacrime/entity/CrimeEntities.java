package dev.otectus.mcacrime.entity;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * This mod's entity types (0.7.2). One so far: the thrown Sand Bottle.
 *
 * <p>The tracking values match vanilla's thrown items — a short range and a slow update interval,
 * because the client extrapolates the arc from its own physics and does not need a correction every
 * tick for something that exists for three seconds.
 */
public final class CrimeEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, McaCrime.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<SandBottleProjectile>> SAND_BOTTLE =
            ENTITIES.register("sand_bottle", () -> EntityType.Builder
                    .<SandBottleProjectile>of(SandBottleProjectile::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build("sand_bottle"));

    private CrimeEntities() {
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }
}
