package dev.otectus.mcacrime.client.render.mask;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.otectus.mcacrime.item.MaskItem;
import dev.otectus.mcacrime.mask.MaskColors;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.Color;

/**
 * The shaped mask, tinted by its dye: one renderer per style, shared by every wearer of that style, so
 * everything it draws is read from the stack GeckoLib prepared it with for this wearer, never cached
 * between wearers.
 *
 * <p>GeckoLib's armour hook draws this once per worn piece and skips vanilla's layer pass, so unlike
 * the Forge 1.20.1 renderer there is no second, untinted overlay draw to suppress.
 *
 * <p>An undyed mask draws its original art untinted. A dyed one (0.8.0) draws the style's neutral dyeable
 * base tinted by the stored colour, then its untinted details over it through {@link DetailLayer}, so a
 * dye colours the cloth, leather, clay or enamel and leaves lenses, eyes, straps and trim their own
 * colours. The stack decides, through {@link MaskColors#hasColor}: a mask dyed white is dyed, and draws
 * the dyed art.
 */
public final class MaskArmorRenderer extends GeoArmorRenderer<MaskItem> {

    public MaskArmorRenderer() {
        super(new MaskArmorModel());
        addRenderLayer(new DetailLayer(this));
    }

    private boolean dyed() {
        return MaskColors.hasColor(getCurrentStack());
    }

    @Override
    public ResourceLocation getTextureLocation(MaskItem mask) {
        return dyed() ? MaskArt.dyed(mask.getVariant()) : MaskArt.original(mask.getVariant());
    }

    @Override
    public Color getRenderColor(MaskItem mask, float partialTick, int packedLight) {
        return Color.ofOpaque(MaskItem.colorOf(getCurrentStack()));
    }

    /**
     * A dyed mask's details, drawn untinted over its tinted base (0.8.0).
     *
     * <p>A GeckoLib render layer runs inside the one armour draw, after the base. {@code reRender} re-poses
     * the same baked model the base just drew -- same bones, same head transform, same armour render type --
     * and the detail texture is transparent everywhere but the details, which the cutout keeps; so the model
     * is not drawn a second time, only its details are drawn over it, the way vanilla lays leather armour's
     * overlay over its tinted base.
     */
    private static final class DetailLayer extends GeoRenderLayer<MaskItem> {
        DetailLayer(MaskArmorRenderer renderer) {
            super(renderer);
        }

        @Override
        public void render(PoseStack pose, MaskItem mask, BakedGeoModel model, RenderType baseType,
                           MultiBufferSource buffers, VertexConsumer baseBuffer, float partialTick,
                           int light, int overlay) {
            MaskArmorRenderer renderer = (MaskArmorRenderer) getRenderer();
            if (!renderer.dyed()) return;
            RenderType detail = renderer.getRenderType(mask, MaskArt.detail(mask.getVariant()), buffers, partialTick);
            renderer.reRender(model, pose, buffers, mask, detail, buffers.getBuffer(detail), partialTick,
                    light, overlay, 0xFFFFFFFF);
        }
    }
}
