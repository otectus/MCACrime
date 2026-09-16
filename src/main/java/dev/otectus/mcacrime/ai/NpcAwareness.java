package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Sleep blocks perception and voluntary action; it never removes protection or legal identity.
 *
 * <p>Sand (0.7.2 §13.5) is deliberately <em>not</em> folded into {@link #isAwake}. A sanded NPC is
 * awake: they talk, they walk, they can be interacted with, and they remain a witness to everything
 * they already saw. Making them read as asleep would have been one line and would have silently
 * disabled conversation, protection and legal identity along with their eyesight. What sand changes
 * is a single question — {@link #canSeeNow} — and every caller that establishes <em>new</em> visual
 * tracking asks that one instead of asking for line of sight directly.
 */
public final class NpcAwareness {
    private NpcAwareness() {}

    public static boolean isAwake(Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive() && !living.isSleeping();
    }

    /**
     * Line of sight for a fresh visual fix, with sand taken into account.
     *
     * <p>Use this where the answer starts or continues live tracking of a target. Do not use it where
     * the answer is about something already known — a remembered identity, a last-seen position, an
     * open case or a target already held — because losing sight of somebody is not forgetting them.
     */
    public static boolean canSeeNow(LivingEntity observer, Entity target) {
        return dev.otectus.mcacrime.effect.SandBlindness.canSee(observer, target);
    }

    /** Clear stale orders before AI ticks without cancelling the tick or changing the sleep state. */
    public static void settleSleeping(LivingEntity entity) {
        if (!entity.isSleeping() || !(entity instanceof Mob mob)
                || !(McaCompat.isMcaVillager(entity) || EntitySelectors.isResponder(entity))) return;
        McaCompat.releaseVillagerControl(entity);
        ReactionSpeedModifier.remove(entity);
        entity.stopUsingItem(); // Abandon a drawn bow without releasing an arrow.
        mob.setAggressive(false);
        mob.setSpeed(0);
        mob.setXxa(0);
        mob.setZza(0);
    }
}
