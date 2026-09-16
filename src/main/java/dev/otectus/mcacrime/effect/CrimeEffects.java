package dev.otectus.mcacrime.effect;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** This mod's mob effects (0.7.2). One so far: {@code mcacrime:sand_blinded}. */
public final class CrimeEffects {

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, McaCrime.MOD_ID);

    public static final RegistryObject<MobEffect> SAND_BLINDED =
            EFFECTS.register("sand_blinded", SandBlindedEffect::new);

    private CrimeEffects() {
    }

    public static void register(IEventBus modBus) {
        EFFECTS.register(modBus);
    }
}
