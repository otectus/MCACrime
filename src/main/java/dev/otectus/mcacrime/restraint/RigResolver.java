package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadLifeStageView;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;

/**
 * The server-side answer to "what body is this, for restraint purposes" (0.7.5 M2.4).
 *
 * <p>Resolved on the server because that is the only side that can: the Townstead bridge binds at
 * {@code ServerStartedEvent} and does not exist on a remote client, so a client asking the same
 * question would answer "ordinary biped" for every subject — correct for almost all of them and
 * wrong for exactly the ones the question was asked about.
 *
 * <p>Unknown resolves to a vanilla humanoid, in both directions, and that asymmetry is deliberate: a
 * wrong "this rig has arms" costs a cosmetic oddity on an unusual body, while a wrong "this rig has
 * no arms" would make every ordinary villager unrestrainable.
 *
 * <p>Names no Townstead type: {@code TownsteadBridge} and its view are this mod's own adapter types,
 * and everything beyond them is reflection inside {@code compat/townstead}.
 */
public final class RigResolver {

    private RigResolver() {
    }

    /**
     * The rig {@code subject} is drawn and restrained on.
     *
     * <p>The scale comes from the subject's own bounding box against the vanilla player's 1.8, so a
     * child body and a tall settlement rig both place their regions correctly without anything here
     * knowing what made them that size.
     */
    public static RigProfile of(@Nullable Entity subject) {
        if (!(subject instanceof LivingEntity living)) {
            return RigProfile.vanillaHumanoid();
        }
        float scale = (float) (living.getBoundingBox().getYsize() / 1.8D);
        String rig = rigId(living);
        boolean humanoid = humanoid(living);
        if (!humanoid) {
            // A declared non-humanoid rig: no arms and no legs to restrain, but a head region that
            // still exists as the top of the body. Nothing is assumed beyond that.
            return new RigProfile(rig.isBlank() ? "non_humanoid" : rig, false, true, false, false, scale);
        }
        return new RigProfile(rig.isBlank() ? "humanoid" : rig, true, true, true, true, scale);
    }

    /** Whether this subject's settlement life stage declares a rig with arms and legs. */
    public static boolean humanoid(@Nullable Entity subject) {
        if (!(subject instanceof LivingEntity living)) {
            return true;
        }
        try {
            return TownsteadBridge.lifeStage(living).asOptional()
                    .map(TownsteadLifeStageView::humanoidRig)
                    .orElse(true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** The declared rig id, empty when the life stage overrode nothing. */
    public static String rigId(@Nullable Entity subject) {
        if (!(subject instanceof LivingEntity living)) {
            return "";
        }
        try {
            return TownsteadBridge.lifeStage(living).asOptional()
                    .map(TownsteadLifeStageView::rig)
                    .orElse("");
        } catch (Throwable ignored) {
            return "";
        }
    }
}
