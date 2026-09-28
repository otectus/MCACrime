package dev.otectus.mcacrime.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * "Restrained" — the display-only badge shown while something is physically on a subject (§3.10).
 *
 * <p><b>Display only.</b> No attribute modifier, no per-tick behaviour, no amplifier that means
 * anything. Every consequence of being restrained comes from {@code restraint/RestrictionResolver}
 * composing the worn definitions into one {@code RestrictionPolicy}; this effect exists so a player
 * can see, in the place they already look for status, that they are wearing something.
 *
 * <p>That separation is deliberate. Upstream packs its restraint state into a
 * {@code RestrainedEffectInstance} amplifier, loses the packing across save and load, and then
 * decides authorisation from it. A {@code MobEffect} is a poor owner for an authorisation decision:
 * milk, a totem death, another mod's cleanse and an operator command can all remove one, and none of
 * those should take a prisoner's handcuffs off. So nothing reads this effect, and removing it by any
 * means changes nothing but the icon — which the next state change puts back.
 */
public final class RestrainedEffect extends MobEffect {

    /** Dull iron: the colour of the cuffs, not of a potion. */
    private static final int COLOR = 0x8A8A8F;

    public RestrainedEffect() {
        super(MobEffectCategory.HARMFUL, COLOR);
    }
}
