package dev.otectus.mcacrime.mixin.client;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientRestraintData;
import dev.otectus.mcacrime.client.render.RestrainedPose;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Poses a restrained entity with their hands behind their back.
 *
 * <p>This is the mod's only mixin, and it exists because no NeoForge event can express the pose.
 * {@code RenderLivingEvent.Pre} fires before {@code setupAnim}, so anything it writes to the arms is
 * overwritten a moment later; and no {@code HumanoidModel.ArmPose} constant represents bound hands, so
 * there is nothing to select either.
 *
 * <p><b>Why the renderer and not the model.</b> Injecting after the {@code setupAnim} call inside
 * {@code LivingEntityRenderer#render} is provably after <em>whatever</em> model that renderer owns has
 * finished animating — including MCA's villager model, which is a {@code HumanoidModel} subclass whose
 * own {@code setupAnim} this mod cannot name and must not depend on the shape of. Injecting at
 * {@code HumanoidModel#setupAnim} TAIL instead would run before any subclass override finished and let
 * MCA re-rotate the arms back; that placement is the documented fallback if this target ever stops
 * resolving, and it is why the target string is spelled out rather than left to a slice.
 *
 * <p>The cost of the wider target is one {@code instanceof} in a hot method, paid once per rendered
 * living entity per frame — cheaper than the map lookup that follows it, which is itself gated behind
 * the config flag.
 *
 * <p><b>Discipline.</b> It reads two things and nothing else: a client cache the server populates, and
 * a client config key. No COMMON config, no server state, no packets, no MCA. It is {@code @Inject},
 * never {@code @Overwrite}, so it composes with every other mod that touches these models. It is
 * listed only in the {@code client} array of {@code mcacrime.mixins.json}, so a dedicated server never
 * loads the class at all.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class RestraintPoseMixin {

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim"
                            + "(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER))
    private void mcacrime$poseRestrained(LivingEntity entity, float entityYaw, float partialTick,
                                         com.mojang.blaze3d.vertex.PoseStack poseStack,
                                         net.minecraft.client.renderer.MultiBufferSource buffers,
                                         int packedLight, CallbackInfo ci) {
        if (!McaCrimeConfig.CLIENT.renderRestraintPose.get()
                || !ClientRestraintData.restrained(entity.getUUID())) {
            return;
        }
        LivingEntityRenderer<?, ?> renderer = (LivingEntityRenderer<?, ?>) (Object) this;
        if (renderer.getModel() instanceof HumanoidModel<?> model) {
            RestrainedPose.apply(model);
        }
    }
}
