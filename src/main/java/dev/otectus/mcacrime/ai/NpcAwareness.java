package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/** Sleep blocks perception and voluntary action; it never removes protection or legal identity. */
public final class NpcAwareness {
    private NpcAwareness() {}

    public static boolean isAwake(Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive() && !living.isSleeping();
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
