package dev.otectus.mcacrime.item.weapon;

import net.minecraft.resources.ResourceLocation;

import java.util.function.Predicate;

/**
 * Everything {@link WeaponRules} is allowed to know about a held item, reduced to primitives.
 *
 * <p>{@code ItemStack} is not on this record on purpose. The classification rules are the part with
 * precedence, config parsing and edge cases worth testing, and they are testable only if they never
 * touch a registry, a tag manager, or an item instance. {@code WeaponDetector} does the touching and
 * hands over the answers; the tag lookup arrives as a predicate for the same reason.
 *
 * @param id                 the item's registry id, or {@code null} if it is somehow unregistered
 * @param hasTag             whether the stack carries a given item tag
 * @param isRestraint        this mod's own restraint, or anything tagged as rope
 * @param isSword            a {@code SwordItem}
 * @param isAxe              an {@code AxeItem}
 * @param isTrident          a {@code TridentItem}
 * @param isProjectileWeapon a {@code ProjectileWeaponItem} (bow, crossbow)
 * @param isDiggerNonAxe     a {@code DiggerItem} that is not an axe — pickaxe, shovel, hoe
 * @param isBlockItem        a {@code BlockItem}
 * @param isStackable        stacks above one, which no firearm does
 * @param attackDamage       bonus attack damage the stack grants in the main hand
 * @param useAnim            {@code UseAnim} name, e.g. {@code BOW}, {@code CROSSBOW}, {@code SPEAR}
 */
public record WeaponProbe(ResourceLocation id,
                          Predicate<ResourceLocation> hasTag,
                          boolean isRestraint,
                          boolean isSword,
                          boolean isAxe,
                          boolean isTrident,
                          boolean isProjectileWeapon,
                          boolean isDiggerNonAxe,
                          boolean isBlockItem,
                          boolean isStackable,
                          double attackDamage,
                          String useAnim) {

    /** Whether the stack carries the given tag id; a probe with no tag source answers no. */
    public boolean tagged(ResourceLocation tag) {
        return hasTag != null && tag != null && hasTag.test(tag);
    }

    public String namespace() {
        return id == null ? "" : id.getNamespace();
    }

    public String path() {
        return id == null ? "" : id.getPath();
    }
}
