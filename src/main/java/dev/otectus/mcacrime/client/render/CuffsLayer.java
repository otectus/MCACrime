package dev.otectus.mcacrime.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientRestraintData;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws cuffs on a restrained player's wrists.
 *
 * <p>Parented to the model's arms rather than positioned in world space, so the cuffs follow whatever
 * pose the arms are in. That matters because the pose comes from a mixin and the mixin is optional:
 * with {@code renderRestraintPose} off, or on a setup where the mixin failed to apply, the cuffs still
 * sit correctly on ordinary swinging arms rather than floating where the arms were expected to be.
 *
 * <p>Reads only {@link ClientRestraintData}, which the server populates. Nothing drawn here can make a
 * player restrained.
 */
public class CuffsLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(McaCrime.id("cuffs"), "main");

    private static final ResourceLocation TEXTURE = McaCrime.id("textures/entity/cuffs.png");

    private final ModelPart rightCuff;
    private final ModelPart leftCuff;

    public CuffsLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent,
                      ModelPart root) {
        super(parent);
        this.rightCuff = root.getChild("right_cuff");
        this.leftCuff = root.getChild("left_cuff");
    }

    /**
     * A band around each wrist, slightly inflated so it does not z-fight the arm underneath.
     *
     * <p>Positioned at the far end of the arm cube: an arm is four blocks long from the shoulder pivot,
     * so the wrist sits at y = 8 in model space.
     */
    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("right_cuff",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-3.0F, 7.0F, -2.5F, 5.0F, 2.0F, 5.0F),
                PartPose.ZERO);
        root.addOrReplaceChild("left_cuff",
                CubeListBuilder.create().texOffs(0, 8)
                        .addBox(-2.0F, 7.0F, -2.5F, 5.0F, 2.0F, 5.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 16);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (!McaCrimeConfig.CLIENT.renderCuffs.get()
                || !ClientRestraintData.restrained(player.getUUID())
                || player.isInvisible()) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = getParentModel();
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));

        rightCuff.copyFrom(model.rightArm);
        leftCuff.copyFrom(model.leftArm);
        rightCuff.render(pose, buffer, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        leftCuff.render(pose, buffer, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
    }
}
