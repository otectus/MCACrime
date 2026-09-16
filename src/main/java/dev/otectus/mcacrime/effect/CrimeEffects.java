package dev.otectus.mcacrime.effect;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * This mod's mob effects (0.7.2). One so far: {@code mcacrime:sand_blinded}.
 *
 * <p>The registration is a {@link DeferredHolder}, which <em>is</em> a {@code Holder<MobEffect>} in
 * 1.21.1. That matters to every caller: {@code hasEffect}, {@code removeEffect} and every
 * {@code MobEffectInstance} constructor take a holder now, so the field is passed straight through
 * rather than unwrapped with {@code get()}.
 */
public final class CrimeEffects {

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, McaCrime.MOD_ID);

    public static final DeferredHolder<MobEffect, SandBlindedEffect> SAND_BLINDED =
            EFFECTS.register("sand_blinded", SandBlindedEffect::new);

    private CrimeEffects() {
    }

    public static void register(IEventBus modBus) {
        EFFECTS.register(modBus);
    }
}
