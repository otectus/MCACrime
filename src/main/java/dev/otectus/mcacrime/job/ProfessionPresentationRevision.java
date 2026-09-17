package dev.otectus.mcacrime.job;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Whether MCA: Crime still owns a villager's visible profession, or somebody else has taken it.
 *
 * <h2>The problem</h2>
 *
 * <p>When this mod labels a criminal it remembers what they used to be, and when the label is no
 * longer warranted it puts that back. The unspoken assumption is that nothing happened in between —
 * true when MCA: Crime was the only thing writing professions, and false the moment a settlement mod,
 * a datapack or a player with a command is also writing them. Restoring unconditionally then means
 * MCA: Crime overwriting somebody else's deliberate change with a value that went stale hours ago,
 * and doing it silently, because a profession revert produces no message.
 *
 * <p>So the revert becomes conditional: put back the old profession only if the villager is still
 * wearing the one this mod wrote. Anything else means a later writer won, and a later writer wins.
 *
 * <h2>Why the expected value is derived rather than stored</h2>
 *
 * <p>It could have been persisted alongside the remembered previous profession, and that is the
 * obvious reading of "record what you wrote". It would also be a new field on a saved record, a new
 * NBT key and a migration, in exchange for a value that is already a pure function of the record:
 * {@link CriminalProfessions#professionIdFor} is total over {@link CriminalJob} and constant, so the
 * profession this mod wrote for a job is the profession it would write for that job now. Deriving it
 * cannot drift, because there is nothing to drift from.
 *
 * <h2>Why an unreadable profession still restores</h2>
 *
 * <p>Failing to read is not evidence that somebody wrote. If MCA's profession accessor is unbound —
 * which is a whole category of degradation this mod is built to survive — every villager would look
 * "changed", every revert would be refused, and a pack that turned the fence label off would leave its
 * shopkeepers labelled forever with no way back. The check is therefore a positive one: it refuses a
 * revert only when it can see a different profession, and falls back to the behaviour this mod has
 * always had whenever it cannot see one at all.
 */
public final class ProfessionPresentationRevision {

    /** What to do with a remembered previous profession. */
    public enum Decision {

        /** The villager still wears what MCA: Crime wrote. Put the old profession back. */
        RESTORE,

        /**
         * Nothing could be compared — the job was never presented, or the current profession could not
         * be read. Restores anyway, which is what this mod did before the check existed.
         */
        RESTORE_UNVERIFIED,

        /** Somebody else wrote a different profession. Leave it, and stop remembering the old one. */
        SKIP_CHANGED;

        /** Whether the previous profession should actually be written back. */
        public boolean restores() {
            return this != SKIP_CHANGED;
        }
    }

    private ProfessionPresentationRevision() {
    }

    /**
     * The decision, given what this mod wrote and what the villager wears now.
     *
     * @param expected the profession MCA: Crime last wrote for this record, or null if it wrote none
     * @param current  the villager's profession right now, or null if it could not be read
     */
    public static Decision decide(@Nullable ResourceLocation expected, @Nullable ResourceLocation current) {
        if (expected == null || current == null) {
            return Decision.RESTORE_UNVERIFIED;
        }
        return expected.equals(current) ? Decision.RESTORE : Decision.SKIP_CHANGED;
    }

    /** The profession this mod puts on a villager holding {@code job}, or null when it labels none. */
    @Nullable
    public static ResourceLocation expectedFor(@Nullable CriminalJob job) {
        return job == null ? null : CriminalProfessions.professionIdFor(job);
    }
}
