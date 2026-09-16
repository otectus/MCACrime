package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.Locale;
import java.util.Optional;

/**
 * What a mask is made of, and the one number that follows from it (0.7.2 §4.2).
 *
 * <p>A family is a <em>cost</em> class, not a stat ladder. Every style inside one conceals exactly the
 * same amount and lasts exactly the same number of uses, because the four styles in a family are made
 * from the same materials by the same recipe and differ only in the face painted on them. Material
 * buys appearance and wear, and nothing else: no defense, no toughness, no sand immunity, no
 * concealment tier. The zero-stat armour material in {@link MaskArmorMaterial} is what makes that
 * structural rather than a promise.
 *
 * <p>Nothing here loads a registry, a sound or an item. The catalogue has to be assertable in a plain
 * JUnit run with no game bootstrapped, so the equip sound -- the one genuinely registry-bound thing a
 * family decides -- lives on {@link MaskArmorMaterial} instead.
 *
 * <p>The wear budgets: clay's 64 and leather's 192 are the shipped 0.7.0 numbers and are deliberately
 * untouched, so no existing mask changes under a player. Cloth is 48 — it costs one wool and one
 * string, the cheapest thing in the station, and a rag over the face should be the one that wears out
 * first. Metal is 256 — two iron ingots is the dearest recipe here, and an iron plate outlasting a
 * leather half-mask is the only ordering a player would guess. They are ratios between the four
 * recipes, not measurements of anything; an operator who dislikes them turns wear off entirely with
 * {@code mask.maskDurabilityEnabled}, which is the switch that genuinely exists.
 */
public enum MaskFamily {

    /** One wool and one string. Cheapest to make, quickest to wear through. */
    CLOTH(48),
    /** Two leather and one string — the 0.7.0 leather mask's own budget, unchanged. */
    LEATHER(192),
    /** Four clay balls and two string — the 0.7.0 clay mask's own budget, unchanged. */
    CLAY(64),
    /** Two iron ingots and one leather. Dearest to make, longest lived. */
    METAL(256);

    private final int durability;

    MaskFamily(int durability) {
        this.durability = durability;
    }

    /** The JSON and tag spelling: lower case, no namespace. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Uses every style in this family gets, before {@code mask.maskDurabilityEnabled} is consulted. */
    public int durability() {
        return durability;
    }

    /** The translation key for this family's name, shown in tooltips and narration. */
    public String displayKey() {
        return "mcacrime.mask.family." + key();
    }

    /** The recipe group every station recipe in this family shares. */
    public String recipeGroup() {
        return McaCrime.MOD_ID + ":" + key() + "_masks";
    }

    /**
     * The {@code mcacrime:masks/<family>} sub-tag.
     *
     * <p>It exists so a restyle recipe can name "any clay mask" in one ingredient. It is deliberately
     * <em>not</em> sufficient on its own: {@code MaskCustomization} additionally requires a registered
     * style, because a pack that drops a third-party helmet into this tag has said "this hides a face",
     * not "this mod may rewrite its NBT".
     */
    public TagKey<Item> tag() {
        return switch (this) {
            case CLOTH -> RestraintTags.MASKS_CLOTH;
            case LEATHER -> RestraintTags.MASKS_LEATHER;
            case CLAY -> RestraintTags.MASKS_CLAY;
            case METAL -> RestraintTags.MASKS_METAL;
        };
    }

    /** Empty when {@code raw} is not one of the four families. */
    public static Optional<MaskFamily> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (MaskFamily family : values()) {
            if (family.key().equals(raw)) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }
}
