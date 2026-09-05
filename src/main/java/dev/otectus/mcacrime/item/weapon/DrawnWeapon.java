package dev.otectus.mcacrime.item.weapon;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * A weapon an entity is actually holding right now, and which hand it is in (0.5.1).
 *
 * <p>The hand is part of the answer rather than an afterthought: the off-hand is a configurable
 * source, so every caller that gates on "is armed" also has to be able to say <em>where</em>, and a
 * bare boolean forces each of them to re-derive it.
 */
public record DrawnWeapon(InteractionHand hand, ItemStack stack, WeaponMatch match) {
}
