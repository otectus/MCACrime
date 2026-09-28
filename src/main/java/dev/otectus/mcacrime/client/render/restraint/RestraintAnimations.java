package dev.otectus.mcacrime.client.render.restraint;

import dev.otectus.mcacrime.client.ClientPhysicalRestraintData;
import dev.otectus.mcacrime.restraint.PhysicalRestraintView;
import dev.otectus.mcacrime.restraint.RestraintDefinition;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * How a restrained body is posed (0.7.5 M2.10), replacing the single {@code RestrainedPose}.
 *
 * <p>Multi-slot, because the state is: a subject may be hooded and cuffed and shackled, and each of
 * those says something different about how the body stands. The pose is derived from the
 * definitions' own render profiles rather than from a flag, so a definition added later poses
 * correctly without a second table to remember.
 *
 * <h2>Why this is not upstream's approach</h2>
 * Upstream poses restrained bodies from a mixin on {@code HumanoidModel#setupAnim}, injected at HEAD
 * and cancellable, writing to {@code head.z} and {@code body.z} on a model instance that is
 * <em>shared between every entity of that type</em> and never restoring them. That is the cause of
 * its missing-skin-second-layer reports: the next entity drawn with the same model inherits the
 * offsets. Everything here writes only to the arms and legs, is applied after the model has finished
 * animating, and touches no shared offset that would have to be undone.
 */
public final class RestraintAnimations {

    /** Arms bound in front: forward, wrists together, hands visible. */
    private static final float FRONT_X_ROT = -0.7F;
    private static final float FRONT_Z_ROT = 0.18F;

    /** Arms bound behind: down, tucked in, pushed past the torso. */
    private static final float BEHIND_Y_ROT = 0.35F;
    private static final float BEHIND_Z_ROT = 0.45F;
    private static final float BEHIND_Z_OFFSET = 2.0F;

    /** Legs bound: ankles pulled together. Radians, model space. */
    private static final float LEGS_Z_ROT = 0.12F;

    private RestraintAnimations() {
    }

    /** The arm poses a restraint can impose. */
    public enum ArmPose {
        FREE,
        /** Weak or loose gear: hands still in front and still usable. */
        ARMS_TIED_FRONT,
        /** Strong gear: hands behind the back, nothing usable. */
        ARMS_TIED_BEHIND
    }

    /**
     * What to draw for one subject.
     *
     * @param arms     which arm pose, if any
     * @param legsTied whether the legs are drawn bound together
     * @param hooded   whether the head carries a hood, for the first-person overlay
     */
    public record Pose(ArmPose arms, boolean legsTied, boolean hooded) {

        public static final Pose FREE = new Pose(ArmPose.FREE, false, false);

        public boolean any() {
            return arms != ArmPose.FREE || legsTied || hooded;
        }
    }

    /**
     * The pose for whatever the client has been told is on {@code subject}.
     *
     * <p>Derived from the definitions' {@code RenderProfile}, which is the same table the server uses
     * to decide restrictions. One source, so a restraint that takes the hands away is always drawn
     * with the hands taken away.
     */
    public static Pose poseFor(UUID subject) {
        PhysicalRestraintView view = ClientPhysicalRestraintData.get(subject).orElse(null);
        if (view == null || view.slots().isEmpty()) {
            return Pose.FREE;
        }
        ArmPose arms = ArmPose.FREE;
        boolean legs = false;
        boolean hooded = false;
        for (RestraintSlot slot : RestraintSlot.values()) {
            RestraintDefinition definition = view.slot(slot)
                    .flatMap(slotView -> RestraintDefinitions.get(slotView.definitionId()))
                    .orElse(null);
            if (definition == null) {
                continue;
            }
            switch (definition.render().pose()) {
                case ARMS_BOUND -> arms = ArmPose.ARMS_TIED_BEHIND;
                // Loose gear only upgrades the pose from free: "behind the back" is the stricter
                // answer and a second, weaker restraint must not relax it.
                case ARMS_LOOSE -> arms = arms == ArmPose.FREE ? ArmPose.ARMS_TIED_FRONT : arms;
                case LEGS_BOUND -> legs = true;
                case HOODED -> hooded = true;
                case DETAINED, NONE -> {
                }
            }
            if (definition.render().firstPersonOverlay()) {
                hooded = true;
            }
        }
        return new Pose(arms, legs, hooded);
    }

    /** Applies {@code pose} to a humanoid model that has already finished animating. */
    public static void apply(HumanoidModel<?> model, Pose pose) {
        if (model == null || pose == null || !pose.any()) {
            return;
        }
        switch (pose.arms()) {
            case ARMS_TIED_BEHIND -> armsBehind(model);
            case ARMS_TIED_FRONT -> armsFront(model);
            case FREE -> {
            }
        }
        if (pose.legsTied()) {
            model.rightLeg.zRot = LEGS_Z_ROT;
            model.leftLeg.zRot = -LEGS_Z_ROT;
        }
        copySleeves(model);
    }

    /** Convenience for the render path: pose {@code entity}'s model from the client's own cache. */
    public static void apply(HumanoidModel<?> model, LivingEntity entity) {
        apply(model, poseFor(entity.getUUID()));
    }

    private static void armsBehind(HumanoidModel<?> model) {
        model.rightArm.xRot = 0.0F;
        model.rightArm.yRot = -BEHIND_Y_ROT;
        model.rightArm.zRot = BEHIND_Z_ROT;
        model.leftArm.xRot = 0.0F;
        model.leftArm.yRot = BEHIND_Y_ROT;
        model.leftArm.zRot = -BEHIND_Z_ROT;
        model.rightArm.z = BEHIND_Z_OFFSET;
        model.leftArm.z = BEHIND_Z_OFFSET;
    }

    private static void armsFront(HumanoidModel<?> model) {
        model.rightArm.xRot = FRONT_X_ROT;
        model.rightArm.yRot = 0.0F;
        model.rightArm.zRot = FRONT_Z_ROT;
        model.leftArm.xRot = FRONT_X_ROT;
        model.leftArm.yRot = 0.0F;
        model.leftArm.zRot = -FRONT_Z_ROT;
        model.rightArm.z = 0.0F;
        model.leftArm.z = 0.0F;
    }

    /**
     * Re-copies each arm into its sleeve overlay.
     *
     * <p>{@code PlayerModel.setupAnim} ends by doing exactly this, and this runs after it. Without
     * repeating it, the outer skin layer keeps the pose the base method left and floats detached from
     * the arm it belongs to — subtle in code, glaring in game, and the same class of bug as upstream's
     * unrestored shared offsets.
     */
    private static void copySleeves(HumanoidModel<?> model) {
        if (model instanceof PlayerModel<?> player) {
            player.rightSleeve.copyFrom(model.rightArm);
            player.leftSleeve.copyFrom(model.leftArm);
            player.rightPants.copyFrom(model.rightLeg);
            player.leftPants.copyFrom(model.leftLeg);
        }
    }
}
