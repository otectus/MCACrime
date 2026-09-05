package dev.otectus.mcacrime.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientRestraintData;
import dev.otectus.mcacrime.enforcement.RestraintVisualType;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * Draws the binding on a restrained subject's wrists.
 *
 * <p>Generic over any {@link HumanoidModel}, which is what lets the same layer sit on a player and on
 * an MCA villager: MCA's villager model is a {@code HumanoidModel} subclass, so the arm parts are in
 * the same places and the cuffs land in the same places too. No MCA type is named — the renderers are
 * found by registry namespace in {@link CrimeRenderLayers}.
 *
 * <p>Parented to the model's arms rather than positioned in world space, so the binding follows
 * whatever pose the arms are in. That matters because the pose comes from a mixin and the mixin is
 * optional: with {@code renderRestraintPose} off, or on a setup where the mixin failed to apply, the
 * cuffs still sit correctly on ordinary swinging arms rather than floating where the arms were
 * expected to be.
 *
 * <p>Rope reuses the cuff geometry with a tint rather than a second texture. A rope band and a metal
 * band are the same shape at the same size; only the colour tells them apart at any distance a player
 * actually looks from.
 *
 * <p>Reads only {@link ClientRestraintData}, which the server populates. Nothing drawn here can make a
 * subject restrained.
 */
public class RestraintWristLayer<T extends LivingEntity, M extends HumanoidModel<T>>
        extends RenderLayer<T, M> {

    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(McaCrime.id("cuffs"), "main");

    private static final ResourceLocation TEXTURE = McaCrime.id("textures/entity/cuffs.png");

    /**
     * Hemp against the grey metal of the untinted texture, packed the way 1.21 wants it.
     *
     * <p>{@code ModelPart.render} takes one ARGB int here rather than the four floats the 1.20 build
     * passed; the constant is written out as a literal so the tint is one value to read rather than a
     * call to reason about. Alpha is opaque, and {@link #NO_TINT} is white, which multiplies to a no-op.
     */
    private static final int ROPE_TINT = 0xFFC79E5C;

    /** White: the cuff texture drawn exactly as authored. */
    private static final int NO_TINT = 0xFFFFFFFF;

    private final ModelPart rightCuff;
    private final ModelPart leftCuff;

    public RestraintWristLayer(RenderLayerParent<T, M> parent, ModelPart root) {
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
    public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        RestraintVisualType type = ClientRestraintData.type(entity.getUUID());
        if (type == RestraintVisualType.NONE
                || !McaCrimeConfig.CLIENT.renderCuffs.get()
                || entity.isInvisible()) {
            return;
        }
        M model = getParentModel();
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));

        int tint = type == RestraintVisualType.ROPE ? ROPE_TINT : NO_TINT;

        rightCuff.copyFrom(model.rightArm);
        leftCuff.copyFrom(model.leftArm);
        rightCuff.render(pose, buffer, light, OverlayTexture.NO_OVERLAY, tint);
        leftCuff.render(pose, buffer, light, OverlayTexture.NO_OVERLAY, tint);
    }
}
