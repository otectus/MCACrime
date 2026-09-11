package dev.otectus.mcacrime.captivity;

/**
 * What has to happen, and in what order, when an NPC is let out of custody.
 *
 * <p>Written as a seam rather than as four statements inside {@code CustodyService.release} because
 * the order is the invariant, and an invariant that can only be checked by starting a server is one
 * that gets quietly broken. The list is deliberately short of the one operation somebody will
 * eventually reach for: there is no "remove the entity". A villager taken into custody is the same
 * villager when it comes out — same identity, same inventory, same family — and the only way to keep
 * that promise is for the release path to have no way of expressing the alternative.
 *
 * <p>The order matters twice over. The lead comes off before MCA's control does, because releasing
 * control first hands the villager back its own brain while it is still tied to a guard. The
 * distraction is cleared whether or not the entity is loaded, because the effect lives in memory keyed
 * on the villager's id and an unloaded chunk is not a reason to leave somebody's witness radius
 * shrunk. The family are told last, once the villager is actually free.
 */
public final class NpcReleaseEffects {

    /** The four things a release does, in the order {@link #apply} does them. */
    public interface Effects {

        /** Cuts the lead. Only meaningful while the villager is loaded. */
        void clearLeash();

        /** Hands the villager back to MCA, so it stops walking to wherever the arrest pointed it. */
        void releaseControl();

        /** Drops any distraction this villager was providing for somebody. */
        void clearDistraction();

        /** Tells the villager's online relatives they are out. Lawful custody only. */
        void notifyFamily();
    }

    private NpcReleaseEffects() {
    }

    /**
     * @param entityLoaded whether the villager is resolvable right now; the two entity-facing effects
     *                     are skipped when it is not, and the other two still run
     * @param lawful       whether this was an arrest rather than a kidnapping; only an arrest is
     *                     something the family were told about in the first place
     */
    public static void apply(Effects effects, boolean entityLoaded, boolean lawful) {
        if (effects == null) {
            return;
        }
        if (entityLoaded) {
            effects.clearLeash();
            effects.releaseControl();
        }
        effects.clearDistraction();
        if (lawful) {
            effects.notifyFamily();
        }
    }
}
