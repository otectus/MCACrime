package dev.otectus.mcacrime.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.entity.PadlockEntity;
import dev.otectus.mcacrime.item.CrimeItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a padlock as its own item, flat against the face it hangs on (M3.4).
 *
 * <p>The item model rather than a bespoke entity model, and deliberately: the padlock's inventory icon
 * is bucket (b) art that already exists, and a hand-authored entity model would need entity textures
 * this release does not ship. An item plane is what vanilla itself uses for item frames' contents, so
 * it reads correctly at a glance and costs no new resource.
 *
 * <p>Every entity type must have a renderer or the client refuses to start, so this class is not
 * optional decoration — it is the registration that makes the padlock exist on a client at all.
 */
public class PadlockRenderer extends EntityRenderer<PadlockEntity> {

    private static final ResourceLocation TEXTURE = McaCrime.id("textures/item/padlock.png");

    public PadlockRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(PadlockEntity padlock, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - padlock.getDirection().toYRot()));
        pose.translate(0.0D, 0.0D, 0.30D);
        pose.scale(0.75F, 0.75F, 0.75F);
        ItemStack stack = new ItemStack(CrimeItems.PADLOCK.get());
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, pose, buffers,
                padlock.level(), padlock.getId());
        pose.popPose();
        super.render(padlock, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(PadlockEntity entity) {
        return TEXTURE;
    }
}
