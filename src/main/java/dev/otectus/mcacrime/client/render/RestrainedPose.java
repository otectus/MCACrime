package dev.otectus.mcacrime.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;

/**
 * The arms-behind-the-back pose, in one place.
 *
 * <p>Split out of the mixin because two things need it and a mixin is the worst possible place to
 * share code from: the pose itself, and the wrist layer that has to sit on top of whatever the pose
 * left behind. Keeping the numbers here means the cuffs and the arms can never be authored against
 * different poses.
 *
 * <p>Client-only, and reachable only from {@code client/} and {@code mixin/client/}.
 */
public final class RestrainedPose {

    /** Straight down, tucked in, and rotated behind the back. Radians, model space. */
    private static final float ARM_X_ROT = 0.0F;
    private static final float ARM_Y_ROT = 0.35F;
    private static final float ARM_Z_ROT = 0.45F;
    /** Pushed back past the torso, which is what reads as "behind the back" from the front. */
    private static final float ARM_Z_OFFSET = 2.0F;

    private RestrainedPose() {
    }

    /**
     * Overwrites both arms with the bound pose.
     *
     * <p>Whatever swing animation {@code setupAnim} just wrote is replaced outright: bound hands do not
     * swing. Applied after the model has finished animating, so this is the last word.
     */
    public static void apply(HumanoidModel<?> model) {
        model.rightArm.xRot = ARM_X_ROT;
        model.rightArm.yRot = -ARM_Y_ROT;
        model.rightArm.zRot = ARM_Z_ROT;
        model.leftArm.xRot = ARM_X_ROT;
        model.leftArm.yRot = ARM_Y_ROT;
        model.leftArm.zRot = -ARM_Z_ROT;
        model.rightArm.z = ARM_Z_OFFSET;
        model.leftArm.z = ARM_Z_OFFSET;

        // PlayerModel.setupAnim ends by copying each arm into its sleeve overlay, and this runs after
        // that copy. Without repeating it the outer skin layer keeps the pose the base method left and
        // floats detached from the arm it belongs to -- subtle in code, glaring in game.
        if (model instanceof PlayerModel<?> player) {
            player.rightSleeve.copyFrom(model.rightArm);
            player.leftSleeve.copyFrom(model.leftArm);
        }
    }
}
