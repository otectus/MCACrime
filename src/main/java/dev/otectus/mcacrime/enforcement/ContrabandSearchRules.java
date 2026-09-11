package dev.otectus.mcacrime.enforcement;

/**
 * Whether a guard searches this player on this pass, decided as a pure function (0.7.0).
 *
 * <p>Six gates, all of which must hold, and every one of them is a fact the caller has already
 * established — the entity queries, the line-of-sight sample and the roll happen in
 * {@link ContrabandSearchService}. The split is the one the whole mod uses: a guard search that fired
 * too often would be a balance complaint nobody could reproduce, and the only way to pin it is to be
 * able to ask the question without a server.
 */
public final class ContrabandSearchRules {

    private ContrabandSearchRules() {
    }

    /**
     * @param losTicks     accumulated ticks of unbroken line of sight this guard has on this player
     * @param requiredLos  {@code searchLosTicksRequired}; 0 means a glance is enough
     * @param roll         a value in [0, 1) from the level's own random
     */
    public static boolean searches(boolean enabled, boolean modeAllowsPatrol, boolean guardInRange,
                                   long losTicks, int requiredLos, boolean requiresSuspicion,
                                   boolean suspicious, double chance, double roll) {
        if (!enabled || !modeAllowsPatrol || !guardInRange) {
            return false;
        }
        if (losTicks < requiredLos) {
            return false;
        }
        if (requiresSuspicion && !suspicious) {
            return false;
        }
        return rolled(chance, roll);
    }

    /** A chance of 0 never fires and a chance of 1 always does, whatever the roll says. */
    public static boolean rolled(double chance, double roll) {
        if (chance <= 0.0D) {
            return false;
        }
        return chance >= 1.0D || roll < chance;
    }

    /**
     * The line-of-sight counter after one pass: it accumulates while the guard can see the player and
     * resets the moment it cannot, because "forty ticks of watching somebody" is not the same promise
     * as "forty ticks, some of them through a wall".
     */
    public static long accumulate(long previous, boolean lineOfSight, int intervalTicks) {
        return lineOfSight ? previous + Math.max(1, intervalTicks) : 0L;
    }
}
