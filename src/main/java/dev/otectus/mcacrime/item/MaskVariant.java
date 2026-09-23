package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The sixteen shipped mask styles: four material families, four faces each (0.7.2 section 4.1).
 *
 * <p>What differs between two styles in the same family is the picture and nothing else. Concealment,
 * wear budget, recipe cost and equip behaviour all come from the {@link MaskFamily}, so a player picks
 * the hockey mask because they want a hockey mask, not because it is the strong one (section 4.2).
 *
 * <p><strong>Ordinals are never persisted.</strong> {@link #styleId()} is the stable namespaced
 * identifier -- it is the item's own registry id, which is also what a saved ItemStack already stores
 * -- and every lookup here goes through it or through the item instance. Reordering this enum, or
 * adding a style in the middle of it, must stay a free operation (section 5.1).
 *
 * <p>Durability is a code constant and not a config value on purpose. {@link
 * net.minecraft.world.item.Item.Properties} is built during the registry event, long before any config
 * file has been read, so a mask whose durability came from config would read whatever the spec's
 * default happened to be and quietly ignore the operator. The switch an operator does get is {@code
 * mask.maskDurabilityEnabled}, which is applied at damage time where the config genuinely is loaded.
 */
public enum MaskVariant {

    /** Folded triangular covering over the lower face. The accessible outlaw classic. */
    BANDANA("bandana", MaskFamily.CLOTH, 0),
    /** A narrow eye mask and discreet ties. */
    HIGHWAYMANS_DOMINO("highwaymans_domino", MaskFamily.CLOTH, 1),
    /** Layered fabric around nose and mouth. */
    WRAPPED_SCARF("wrapped_scarf", MaskFamily.CLOTH, 2),
    /** A light, draped lower-face silhouette. */
    HALF_VEIL("half_veil", MaskFamily.CLOTH, 3),

    /**
     * The 0.7.0 leather mask, which is the Cutpurse Mask of the launch collection (section 4.1).
     *
     * <p>Kept under its original {@code leather_mask} id and its original 192-use budget rather than
     * reissued under a prettier one: a saved stack, a datapack loot table and a crafting-table recipe
     * all name that id, and duplicating a working mask to satisfy a table is exactly what section 4.1
     * forbids.
     */
    LEATHER("leather_mask", MaskFamily.LEATHER, 0),
    /** A plague-doctor mask with a long beak and goggles; it filters nothing. */
    RAVEN("raven_mask", MaskFamily.LEATHER, 1),
    /** Angular cheeks and short stylized ears. */
    JACKAL("jackal_mask", MaskFamily.LEATHER, 2),
    /** Patchwork panels and visible coarse stitching. */
    STITCHED("stitched_mask", MaskFamily.LEATHER, 3),

    /** A generic perforated pale faceplate. */
    HOCKEY("hockey_mask", MaskFamily.CLAY, 0),
    /**
     * The 0.7.0 clay mask, which is the Blank Clay Mask of the launch collection -- original id,
     * original 64-use budget, for the same reason {@link #LEATHER} keeps hers.
     */
    CLAY("clay_mask", MaskFamily.CLAY, 1),
    /** A readable smiling theatrical face. */
    COMEDY("comedy_mask", MaskFamily.CLAY, 2),
    /** A contrasting sorrowful theatrical face. */
    TRAGEDY("tragedy_mask", MaskFamily.CLAY, 3),

    /** Balaclava reinterpretation; the legacy ID and crafting family remain save-compatible. */
    IRON_SKULL("iron_skull_mask", MaskFamily.METAL, 0),
    /** A simple riveted faceplate with a horizontal opening. */
    BRIGAND_VISOR("brigand_visor", MaskFamily.METAL, 1),
    /** Compact brow and symmetrical eye forms. */
    OWL("owl_mask", MaskFamily.METAL, 2),
    /** An austere metal plate with minimal features. */
    BLANK_IRON("blank_iron_mask", MaskFamily.METAL, 3);

    private final String textureName;
    private final MaskFamily family;
    private final int sortOrder;

    MaskVariant(String textureName, MaskFamily family, int sortOrder) {
        this.textureName = textureName;
        this.family = family;
        this.sortOrder = sortOrder;
    }

    /** The registry path, and the stem of both the item and worn-layer textures. */
    public String textureName() {
        return textureName;
    }

    /** The stable namespaced style id -- {@code mcacrime:clay_mask} -- never an ordinal. */
    public String styleId() {
        return McaCrime.MOD_ID + ":" + textureName;
    }

    public MaskFamily family() {
        return family;
    }

    /** Where this style sits in its family's row of four, in the station catalogue. */
    public int sortOrder() {
        return sortOrder;
    }

    /** Every style takes dye; an undyed one is untinted rather than leather-brown (section 5.3). */
    public boolean tintable() {
        return true;
    }

    /** The family's wear budget. Equal for all four styles in a family, by construction. */
    public int durability() {
        return family.durability();
    }

    /** The translation key of this style's item name. */
    public String displayKey() {
        return "item." + McaCrime.MOD_ID + "." + textureName;
    }

    /** Every style in one family, in catalogue order. */
    public static List<MaskVariant> of(MaskFamily family) {
        return Arrays.stream(values()).filter(variant -> variant.family == family)
                .sorted(Comparator.comparingInt(MaskVariant::sortOrder)).toList();
    }

    /** The style with this namespaced id, or empty -- never a positional guess. */
    public static Optional<MaskVariant> byStyleId(@Nullable String styleId) {
        if (styleId == null) {
            return Optional.empty();
        }
        for (MaskVariant variant : values()) {
            if (variant.styleId().equals(styleId)) {
                return Optional.of(variant);
            }
        }
        return Optional.empty();
    }

    /**
     * The registered style this item is, or empty when it is not one of this mod's masks.
     *
     * <p>Reads the item instance rather than the registry, so it answers with no game bootstrapped and,
     * more importantly, cannot be spoofed: a tag entry or a client-supplied NBT field is not a
     * registered style, and only a registered style may use the protected restyle path (section 7.4).
     */
    public static Optional<MaskVariant> byItem(@Nullable Item item) {
        return item instanceof MaskItem mask ? Optional.of(mask.getVariant()) : Optional.empty();
    }

    /** The registered style of the item in this stack, or empty. */
    public static Optional<MaskVariant> byStack(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? Optional.empty() : byItem(stack.getItem());
    }
}
