package dev.otectus.mcacrime.client.render.mask;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.item.MaskItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public final class MaskArmorModel extends GeoModel<MaskItem> {
    @Override
    public ResourceLocation getModelResource(MaskItem mask) {
        return McaCrime.id("geo/masks/" + mask.getVariant().textureName() + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(MaskItem mask) {
        return MaskArt.original(mask.getVariant());
    }

    @Override
    public ResourceLocation getAnimationResource(MaskItem mask) {
        return McaCrime.id("animations/masks.animation.json");
    }
}
