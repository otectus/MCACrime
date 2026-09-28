package dev.otectus.mcacrime.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.otectus.mcacrime.entity.ChainKnotEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Draws a chain knot as a small chain item at the post it hangs on (0.7.5 M4.2).
 *
 * <p>The same choice {@code PadlockRenderer} made in M3.4, for the same reason: the item art already
 * exists, an item plane is what vanilla itself uses for an item frame's contents, and a bespoke entity
 * model would need an entity texture this release does not ship. The knot is deliberately small — it
 * is the anchor point of a chain, not a decoration in its own right.
 *
 * <p>Not optional: a client refuses to start with an entity type that has no renderer, so this class
 * is the registration that makes the knot exist on a client at all.
 */
public class ChainKnotRenderer extends EntityRenderer<ChainKnotEntity> {

    /** 1.21 made {@code ResourceLocation}'s constructor private; this is the vanilla-namespace form. */
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/item/chain.png");

    public ChainKnotRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ChainKnotEntity knot, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0.0D, 0.25D, 0.0D);
        pose.scale(0.5F, 0.5F, 0.5F);
        Minecraft.getInstance().getItemRenderer().renderStatic(new ItemStack(Items.CHAIN),
                ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers,
                knot.level(), knot.getId());
        pose.popPose();
        super.render(knot, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(ChainKnotEntity knot) {
        return TEXTURE;
    }
}
