package dev.otectus.mcacrime.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * "Sand in Eyes" — the dedicated harmful effect a Sand Bottle applies (0.7.2 §13.4).
 *
 * <p>Its own effect rather than vanilla Blindness on purpose. The awareness layer, the recovery
 * ledger and the incident provenance all have to be able to ask "is this <em>sand</em>?", and a mob
 * that was already blind for an unrelated reason must not become this mod's problem. Nothing is
 * overridden here: the effect carries no attribute modifiers and no per-tick behaviour, because every
 * consequence it has is a perception rule somewhere else asking whether it is present.
 */
public final class SandBlindedEffect extends MobEffect {

    /** A dusty pale ochre, so the inventory swatch reads as sand rather than as a potion colour. */
    private static final int COLOR = 0xC2B280;

    public SandBlindedEffect() {
        super(MobEffectCategory.HARMFUL, COLOR);
    }
}
