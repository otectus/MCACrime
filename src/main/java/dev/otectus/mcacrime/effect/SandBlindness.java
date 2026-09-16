package dev.otectus.mcacrime.effect;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The one question the awareness layer asks about sand: can this observer still see that (0.7.2 §13.5).
 *
 * <p>Sand blocks <em>new detailed visual observation</em> beyond arm's reach. It does not delete
 * targets, clear goals, freeze navigation or stand in for sleep — a blinded guard still walks toward
 * where the suspect was, still knows who they are looking for, and still reacts to somebody standing
 * against them. That is the whole difference between blindness and paralysis, and every call site in
 * this mod goes through here so the difference is stated once.
 */
public final class SandBlindness {

    private SandBlindness() {
    }

    /** Whether this entity currently has sand in its eyes. */
    public static boolean isBlinded(Entity entity) {
        return entity instanceof LivingEntity living
                && CrimeEffects.SAND_BLINDED.isBound()
                && living.hasEffect(CrimeEffects.SAND_BLINDED);
    }

    /**
     * Whether sand stops this observer forming a fresh visual fix on that target.
     *
     * <p>Close contact is exempt: somebody immediately beside you is felt, heard and blundered into
     * whether or not your eyes are working.
     */
    public static boolean blocksSight(Entity observer, Entity target) {
        if (target == null || !isBlinded(observer)) {
            return false;
        }
        return !SandExposurePolicy.withinCloseContact(Math.sqrt(observer.distanceToSqr(target)));
    }

    /**
     * Line of sight with sand taken into account — the replacement for a bare
     * {@code hasLineOfSight} on any path that establishes <em>new</em> visual tracking.
     *
     * <p>Paths that read remembered identity, a last-seen position or an already-held target
     * deliberately do not call this: losing sight of somebody is not forgetting them (invariant 12).
     */
    public static boolean canSee(LivingEntity observer, Entity target) {
        return observer != null && target != null && observer.hasLineOfSight(target)
                && !blocksSight(observer, target);
    }
}
