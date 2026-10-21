package dev.otectus.mcacrime.client.render.mask;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.item.MaskVariant;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Every style's worn textures, named once (0.8.0): the original art an undyed mask draws, and the
 * neutral base and untinted detail a dyed one draws instead.
 *
 * <p>Three immutable tables built at class load, not a location per frame: the textures themselves are
 * ordinary resource-pack files that the texture manager reloads, so nothing here has to follow a reload.
 * The dyed pair is authored by {@code tools/art/build_mask_dye_layers.py}, which explains how each
 * style was split.
 */
final class MaskArt {

    private static final Map<MaskVariant, ResourceLocation> ORIGINAL = table("_layer_1");
    private static final Map<MaskVariant, ResourceLocation> DYED = table("_dyed");
    private static final Map<MaskVariant, ResourceLocation> DETAIL = table("_dyed_detail");

    private MaskArt() {
    }

    private static Map<MaskVariant, ResourceLocation> table(String suffix) {
        EnumMap<MaskVariant, ResourceLocation> table = new EnumMap<>(MaskVariant.class);
        for (MaskVariant variant : MaskVariant.values()) {
            table.put(variant, McaCrime.id("textures/models/armor/" + variant.textureName() + suffix + ".png"));
        }
        return Collections.unmodifiableMap(table);
    }

    /** The art an undyed mask has always drawn. */
    static ResourceLocation original(MaskVariant variant) {
        return ORIGINAL.get(variant);
    }

    /** A dyed mask's dyeable surface, in neutral greys for the dye to colour. */
    static ResourceLocation dyed(MaskVariant variant) {
        return DYED.get(variant);
    }

    /** A dyed mask's eyes, lenses, straps and trim, drawn untinted over the base; transparent elsewhere. */
    static ResourceLocation detail(MaskVariant variant) {
        return DETAIL.get(variant);
    }
}
