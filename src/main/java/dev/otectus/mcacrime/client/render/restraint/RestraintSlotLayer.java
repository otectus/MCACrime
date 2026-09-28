package dev.otectus.mcacrime.client.render.restraint;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientPhysicalRestraintData;
import dev.otectus.mcacrime.client.ClientRestraintRig;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws every restraint a subject is wearing (0.7.5 M2.10), replacing {@code RestraintWristLayer}.
 *
 * <p>One layer for all three slots, because the state is three slots: the layer it replaces could
 * draw one thing, so a hooded and shackled prisoner was drawn as whichever the old enum happened to
 * hold. Here each occupied slot resolves to its own model and its own texture and all of them are
 * drawn.
 *
 * <p>Generic over any {@link HumanoidModel}, which is what lets the same layer sit on a player and on
 * an MCA villager — MCA's villager model is a {@code HumanoidModel} subclass, so the parts are in the
 * same places. No MCA type is named; the renderers are found by registry namespace in
 * {@code client/render/CrimeRenderLayers}.
 *
 * <p>Reads only {@link ClientPhysicalRestraintData}, which the server populates. Nothing drawn here
 * can make a subject restrained, and a client visual toggle can never lift a server restriction.
 *
 * <h2>Bodies with no wrists</h2>
 * A settlement life stage may declare a rig with no arms, and bands parented to arms that are not
 * there would hang in the air beside the body. {@link ClientRestraintRig} answers that question, and
 * when the answer is no the worn models are replaced by a single band round the middle of the hitbox
 * — derived from {@code getBbWidth}/{@code getBbHeight} rather than from a skeleton, so it lands
 * correctly on a body this mod knows nothing about.
 */
public class RestraintSlotLayer<T extends LivingEntity, M extends HumanoidModel<T>>
        extends RenderLayer<T, M> {

    /**
     * Model {@code y = 0} is 1.501 blocks above the feet and grows downwards:
     * {@code LivingEntityRenderer} flips the pose in Y and lifts it before a layer runs.
     */
    private static final float MODEL_ORIGIN_HEIGHT = 1.501F;
    private static final float UNITS_PER_BLOCK = 16.0F;

    /** The fallback band's own half-width in model units, matching the band geometry. */
    private static final float BAND_HALF_WIDTH = 2.5F;

    /** One baked root per definition. Baked per renderer: two renderers must not share a part. */
    private final Map<ResourceLocation, ModelPart> baked = new HashMap<>();

    private final ModelPart fallbackBand;

    public RestraintSlotLayer(RenderLayerParent<T, M> parent, EntityModelSet models) {
        super(parent);
        RestraintModels.all().forEach((definitionId, worn) ->
                baked.put(definitionId, models.bakeLayer(worn.layer())));
        // The fallback reuses the wrist model's right-arm band, which is the same 5x2x5 ring: one
        // piece of geometry rather than a tenth layer that would have to be kept in step with it.
        ModelPart handcuffs = baked.get(dev.otectus.mcacrime.restraint.RestraintDefinitions.HANDCUFFS_ARMS);
        this.fallbackBand = handcuffs == null ? null
                : handcuffs.getChild(RestraintModels.RIGHT_ARM);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        PhysicalRestraintView view = ClientPhysicalRestraintData.get(entity.getUUID()).orElse(null);
        if (view == null || view.slots().isEmpty() || entity.isInvisible()
                || !McaCrimeConfig.CLIENT.renderWornRestraints.get()) {
            return;
        }
        M model = getParentModel();
        if (!hasLimbs(model, entity)) {
            renderFallbackBand(pose, buffers, light, entity);
            return;
        }
        for (RestraintSlot slot : RestraintSlot.values()) {
            view.slot(slot).ifPresent(slotView -> renderWorn(pose, buffers, light, model,
                    slotView.definitionId()));
        }
    }

    /** Draws one definition's worn model, copying the body's transforms into its attachment parts. */
    private void renderWorn(PoseStack pose, MultiBufferSource buffers, int light, M model,
                            ResourceLocation definitionId) {
        RestraintModels.Worn worn = RestraintModels.forDefinition(definitionId).orElse(null);
        ModelPart root = baked.get(definitionId);
        if (worn == null || root == null) {
            return; // a definition this build draws nothing for; silence is the correct answer
        }
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(worn.texture()));
        for (String attachment : worn.attachments()) {
            ModelPart part = child(root, attachment);
            ModelPart source = bodyPart(model, attachment);
            if (part == null || source == null || !source.visible) {
                continue;
            }
            part.copyFrom(source);
            part.render(pose, buffer, light, OverlayTexture.NO_OVERLAY);
        }
    }

    @Nullable
    private static ModelPart child(ModelPart root, String name) {
        return root.hasChild(name) ? root.getChild(name) : null;
    }

    @Nullable
    private ModelPart bodyPart(M model, String attachment) {
        return switch (attachment) {
            case RestraintModels.HEAD -> model.head;
            case RestraintModels.BODY -> model.body;
            case RestraintModels.RIGHT_ARM -> model.rightArm;
            case RestraintModels.LEFT_ARM -> model.leftArm;
            case RestraintModels.RIGHT_LEG -> model.rightLeg;
            case RestraintModels.LEFT_LEG -> model.leftLeg;
            default -> null;
        };
    }

    /**
     * Whether there are limbs to hang gear on.
     *
     * <p>Two independent questions, because they fail in different worlds. The model's own arm
     * visibility catches a body whose renderer hid the vanilla parts to draw something else, whatever
     * mod did that. The settlement rig catches a life stage that declares a different model outright,
     * which is knowable before anything is drawn.
     */
    private boolean hasLimbs(M model, T entity) {
        return model.rightArm.visible && model.leftArm.visible && ClientRestraintRig.humanoid(entity);
    }

    /**
     * One band round the middle of the hitbox, for a body with no limbs.
     *
     * <p>Sized and placed from the hitbox rather than from model parts, which is the whole point:
     * there is no skeleton to trust here, and a hitbox is the one description of the body every entity
     * has.
     */
    private void renderFallbackBand(PoseStack pose, MultiBufferSource buffers, int light, T entity) {
        if (fallbackBand == null) {
            return;
        }
        RestraintModels.Worn worn = RestraintModels
                .forDefinition(dev.otectus.mcacrime.restraint.RestraintDefinitions.HANDCUFFS_ARMS)
                .orElse(null);
        if (worn == null) {
            return;
        }
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(worn.texture()));
        float half = Math.max(0.05F, entity.getBbWidth() * 0.5F);
        float scale = (half * UNITS_PER_BLOCK + 0.6F) / BAND_HALF_WIDTH;
        float y = MODEL_ORIGIN_HEIGHT - entity.getBbHeight() * 0.5F;

        fallbackBand.loadPose(PartPose.ZERO);
        pose.pushPose();
        pose.translate(0.0F, y, 0.0F);
        pose.scale(scale, 1.0F, scale);
        fallbackBand.render(pose, buffer, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}
