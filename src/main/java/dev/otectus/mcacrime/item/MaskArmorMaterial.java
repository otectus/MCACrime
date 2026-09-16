package dev.otectus.mcacrime.item;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * The zero-stat armour material one per mask family (0.7.0, extended 0.7.2 section 4.2).
 *
 * <p>A mask is a legal-layer item, not a combat item: every family grants no defense, no toughness and
 * no knockback resistance, and takes no enchantments. The only thing a family changes here is the
 * sound it makes going on -- cloth and leather rustle, clay clicks, iron clanks -- which is flavour,
 * not a stat. Durability is deliberately 0 here and supplied per family through {@code
 * Item.Properties#durability}, which {@code ArmorItem} leaves alone once it is set.
 *
 * <p>{@link #getName()} is a plain lowercase string with no colon. Vanilla builds the worn-armour
 * texture path out of it, and a colon there produces a path no resource pack can ever satisfy -- a
 * silently invisible mask rather than a missing-texture one. {@code MaskItem} overrides the texture
 * lookup anyway, so these names are only ever a fallback.
 */
public enum MaskArmorMaterial implements ArmorMaterial {

    CLOTH(MaskFamily.CLOTH),
    LEATHER(MaskFamily.LEATHER),
    CLAY(MaskFamily.CLAY),
    METAL(MaskFamily.METAL);

    private final MaskFamily family;

    MaskArmorMaterial(MaskFamily family) {
        this.family = family;
    }

    /** The material a style's family uses. */
    public static MaskArmorMaterial of(MaskFamily family) {
        return switch (family) {
            case CLOTH -> CLOTH;
            case LEATHER -> LEATHER;
            case CLAY -> CLAY;
            case METAL -> METAL;
        };
    }

    @Override
    public int getDurabilityForType(ArmorItem.Type type) {
        return 0; // set per family on Item.Properties instead
    }

    @Override
    public int getDefenseForType(ArmorItem.Type type) {
        return 0;
    }

    @Override
    public int getEnchantmentValue() {
        return 0;
    }

    @Override
    public SoundEvent getEquipSound() {
        return switch (family) {
            case CLOTH, LEATHER -> SoundEvents.ARMOR_EQUIP_LEATHER;
            case CLAY -> SoundEvents.ARMOR_EQUIP_GENERIC;
            case METAL -> SoundEvents.ARMOR_EQUIP_IRON;
        };
    }

    @Override
    public Ingredient getRepairIngredient() {
        return Ingredient.EMPTY;
    }

    @Override
    public String getName() {
        return "mcacrime_mask_" + family.key();
    }

    @Override
    public float getToughness() {
        return 0.0F;
    }

    @Override
    public float getKnockbackResistance() {
        return 0.0F;
    }
}
