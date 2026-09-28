package dev.otectus.mcacrime.effect;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * This mod's mob effects: {@code mcacrime:sand_blinded} (0.7.2) and {@code mcacrime:restrained}
 * (0.7.5 §3.10).
 *
 * <p>The second one is a badge and nothing else — see {@link RestrainedEffect} for why a mob effect
 * is deliberately not where restraint state lives.
 */
public final class CrimeEffects {

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, McaCrime.MOD_ID);

    public static final RegistryObject<MobEffect> SAND_BLINDED =
            EFFECTS.register("sand_blinded", SandBlindedEffect::new);

    /** Shown while anything is worn. Display only; {@code RestrictionResolver} is the authority. */
    public static final RegistryObject<MobEffect> RESTRAINED =
            EFFECTS.register("restrained", RestrainedEffect::new);

    private CrimeEffects() {
    }

    public static void register(IEventBus modBus) {
        EFFECTS.register(modBus);
    }
}
