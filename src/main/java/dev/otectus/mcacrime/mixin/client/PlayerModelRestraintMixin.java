package dev.otectus.mcacrime.mixin.client;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientRestraintData;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Poses a restrained player with their hands behind their back.
 *
 * <p>This is the mod's only mixin, and it exists because no Forge event can express the pose.
 * {@code RenderPlayerEvent.Pre} fires before {@code PlayerRenderer.setModelProperties} and before
 * {@code setupAnim}, so anything it writes to the arms is overwritten a moment later; and no
 * {@code HumanoidModel.ArmPose} constant represents bound hands, so there is nothing to select either.
 *
 * <p>{@code PlayerModel#setupAnim} is the target rather than {@code HumanoidModel#setupAnim} because
 * the base method also runs for zombies, villagers and armour stands, which would mean an
 * {@code instanceof} check in one of the hottest methods in the renderer for the sake of a case that
 * almost never applies.
 *
 * <p><b>Discipline.</b> It reads two things and nothing else: a client cache the server populates, and
 * a client config key. No COMMON config, no server state, no packets, no MCA. It is
 * {@code @Inject}, never {@code @Overwrite}, so it composes with every other mod that touches the
 * player model. It is listed only in the {@code client} array of {@code mcacrime.mixins.json}, so a
 * dedicated server never loads the class at all.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelRestraintMixin {

    /**
     * Whether the injection has already announced itself.
     *
     * <p>A mixin that fails to apply is silent: the arms simply swing and nothing in the log says the
     * feature is off. One debug line the first time the injector runs is the difference between
     * "restraint poses are broken" and "the mixin never applied", and it is written once rather than
     * sixty times a second because this method is on the render path.
     */
    private static boolean mcacrime$logged;

    @Shadow
    public ModelPart leftSleeve;

    @Shadow
    public ModelPart rightSleeve;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void mcacrime$poseRestrained(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                         float ageInTicks, float netHeadYaw, float headPitch,
                                         CallbackInfo ci) {
        if (!mcacrime$logged) {
            mcacrime$logged = true;
            McaCrime.LOGGER.debug("PlayerModelRestraintMixin applied");
        }
        if (!(entity instanceof AbstractClientPlayer player)) {
            return;
        }
        if (!McaCrimeConfig.CLIENT.renderRestraintPose.get()
                || !ClientRestraintData.restrained(player.getUUID())) {
            return;
        }
        PlayerModel<?> model = (PlayerModel<?>) (Object) this;

        // Arms straight down, tucked in and rotated behind the back. Whatever swing animation the base
        // method just wrote is replaced outright: bound hands do not swing.
        model.rightArm.xRot = 0.0F;
        model.rightArm.yRot = -0.35F;
        model.rightArm.zRot = 0.45F;
        model.leftArm.xRot = 0.0F;
        model.leftArm.yRot = 0.35F;
        model.leftArm.zRot = -0.45F;
        model.rightArm.z = 2.0F;
        model.leftArm.z = 2.0F;

        // PlayerModel.setupAnim ends by copying each arm into its sleeve overlay, and this injection
        // runs after that copy. Without repeating it the outer skin layer keeps the pose the base
        // method left and floats detached from the arm it belongs to -- subtle in code, glaring in game.
        rightSleeve.copyFrom(model.rightArm);
        leftSleeve.copyFrom(model.leftArm);
    }
}
