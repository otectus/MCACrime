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
import net.minecraft.client.renderer.texture.OverlayTexture;
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
 *
 * <p>Where it is drawn is the entity's decision ({@code PadlockPlacement}): the entity keeps its own
 * position inside the locked block and hands this renderer the offset to the surface. This line's icon
 * is already drawn upright, shackle on top, so unlike the Forge 1.20.1 line's diagonal icon it is not
 * turned.
 */
public class PadlockRenderer extends EntityRenderer<PadlockEntity> {

    private static final ResourceLocation TEXTURE = McaCrime.id("textures/item/padlock.png");
    /** About seven pixels tall: the size of the lock plate it hangs from. */
    private static final float SCALE = 0.75F;

    public PadlockRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(PadlockEntity padlock, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        pose.pushPose();
        net.minecraft.world.phys.Vec3 offset = padlock.renderOffset();
        pose.translate(offset.x, offset.y, offset.z);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - padlock.getDirection().toYRot()));
        pose.scale(SCALE, SCALE, SCALE);
        ItemStack stack = new ItemStack(CrimeItems.PADLOCK.get());
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, padlock.level(), padlock.getId());
        pose.popPose();
        super.render(padlock, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(PadlockEntity entity) {
        return TEXTURE;
    }
}
