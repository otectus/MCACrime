package dev.otectus.mcacrime.client.render.mask;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.otectus.mcacrime.item.MaskItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.core.object.Color;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

public final class MaskArmorRenderer extends GeoArmorRenderer<MaskItem> {
    private boolean rendered;

    public MaskArmorRenderer() {
        super(new MaskArmorModel());
    }

    @Override
    public Color getRenderColor(MaskItem mask, float partialTick, int packedLight) {
        return Color.ofOpaque(mask.getColor(getCurrentStack()));
    }

    @Override
    public void prepForRender(Entity wearer, ItemStack stack, EquipmentSlot slot, HumanoidModel<?> original) {
        rendered = false;
        super.prepForRender(wearer, stack, slot, original);
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay,
                               float red, float green, float blue, float alpha) {
        // Vanilla draws DyeableLeatherItem twice (tinted base, then untinted overlay).
        // Our Geo model supplies its own texture and tint, so draw it once per armor invocation.
        if (rendered) return;
        rendered = true;
        super.renderToBuffer(pose, buffer, light, overlay, red, green, blue, alpha);
    }
}
