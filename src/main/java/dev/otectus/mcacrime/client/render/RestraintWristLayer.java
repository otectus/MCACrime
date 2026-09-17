package dev.otectus.mcacrime.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientRestraintData;
import dev.otectus.mcacrime.client.ClientRestraintRig;
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
 *
 * <h2>Bodies with no wrists</h2>
 *
 * <p>The arm assumption is not universal once a settlement mod can give a life stage its own rig: a
 * villager may be rendered as an egg or a grub, and two bands parented to arms that are not there
 * would hang in the air beside them. So the rig is consulted through {@link ClientRestraintRig}, and
 * when it is not humanoid — or when the parent model's arms have been hidden by whatever is drawing
 * that body — the wrist cuffs are replaced by a single band around the middle of the hitbox: the
 * tether anchor a lead would be tied to. It is derived from {@code getBbWidth}/{@code getBbHeight}
 * rather than from a skeleton, so it lands correctly on a body this mod knows nothing about, and it
 * keeps the same texture and the same rope/metal tint so the restraint still reads at a glance.
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

    /**
     * Where the tether band sits in model space.
     *
     * <p>{@code LivingEntityRenderer} flips the pose in Y and lifts it by 1.501 blocks before a layer
     * runs, so model {@code y = 0} is 1.501 blocks above the entity's feet and {@code y} grows
     * downwards. Sixteen model units to the block, as everywhere else in a Minecraft model.
     */
    private static final float MODEL_ORIGIN_HEIGHT = 1.501F;
    private static final float UNITS_PER_BLOCK = 16.0F;

    /** The band's own half-width in model units, matching the cuff geometry below. */
    private static final float BAND_HALF_WIDTH = 2.5F;

    private final ModelPart rightCuff;
    private final ModelPart leftCuff;
    private final ModelPart tetherBand;

    public RestraintWristLayer(RenderLayerParent<T, M> parent, ModelPart root) {
        super(parent);
        this.rightCuff = root.getChild("right_cuff");
        this.leftCuff = root.getChild("left_cuff");
        this.tetherBand = root.getChild("tether_band");
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
        // The tether band: the same 5x2x5 ring at the same texture offset as a cuff, centred on its
        // own origin so it can be scaled to whatever body it has to go round.
        root.addOrReplaceChild("tether_band",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-2.5F, -1.0F, -2.5F, 5.0F, 2.0F, 5.0F),
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

        if (!hasWrists(model, entity)) {
            renderTetherBand(pose, buffer, light, entity, tint);
            return;
        }

        rightCuff.copyFrom(model.rightArm);
        leftCuff.copyFrom(model.leftArm);
        rightCuff.render(pose, buffer, light, OverlayTexture.NO_OVERLAY, tint);
        leftCuff.render(pose, buffer, light, OverlayTexture.NO_OVERLAY, tint);
    }

    /**
     * Whether there are arms to put cuffs on.
     *
     * <p>Two independent questions, because they fail in different worlds. The model's own arm
     * visibility catches a body whose renderer hid the vanilla parts to draw something else, which is
     * true whatever mod did it. The Townstead rig catches a life stage that declares a different model
     * outright, which is knowable before anything is drawn.
     */
    private boolean hasWrists(M model, T entity) {
        return model.rightArm.visible && model.leftArm.visible && ClientRestraintRig.humanoid(entity);
    }

    /**
     * One band round the middle of the hitbox, for a body with no wrists.
     *
     * <p>Sized and placed from {@code getBbWidth}/{@code getBbHeight} rather than from model parts,
     * which is the whole point: there is no skeleton to trust here, and a hitbox is the one description
     * of the body that every entity has. The band is scaled to sit just outside the hitbox so it reads
     * as something fastened round the creature rather than as a texture on it.
     */
    private void renderTetherBand(PoseStack pose, VertexConsumer buffer, int light, T entity, int tint) {
        float half = Math.max(0.05F, entity.getBbWidth() * 0.5F);
        float scale = (half * UNITS_PER_BLOCK + 0.6F) / BAND_HALF_WIDTH;
        // Model y grows downwards from 1.501 blocks above the feet; half the body height up from the
        // feet is therefore that offset minus half the height, in model units.
        float y = (MODEL_ORIGIN_HEIGHT - entity.getBbHeight() * 0.5F) * UNITS_PER_BLOCK;

        tetherBand.loadPose(PartPose.ZERO);
        pose.pushPose();
        pose.translate(0.0F, y / UNITS_PER_BLOCK, 0.0F);
        pose.scale(scale, 1.0F, scale);
        tetherBand.render(pose, buffer, light, OverlayTexture.NO_OVERLAY, tint);
        pose.popPose();
    }
}
