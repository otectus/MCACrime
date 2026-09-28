package dev.otectus.mcacrime.stat;

import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.StatFormatter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Which statistics exist and what each one counts (M5.11, Appendix A.3).
 *
 * <p>Separate from {@link CrimeStats} so the catalogue can be reasoned about without touching a
 * registry: {@code CrimeStats} holds a {@code DeferredRegister}, and merely naming that class pulls
 * in the whole registry bootstrap. The fifteen ids, the three kinds and the definition-to-family
 * mapping are all decisions rather than registrations, and they belong where they can be checked.
 */
public final class CrimeStatIds {

    /** What a restraint statistic counts. One triple per family. */
    public enum Kind {
        /** How many times this family was put on them. */
        TIMES_RESTRAINED("times_restrained", StatFormatter.DEFAULT),
        /** How many of this family they broke out of. */
        BROKEN("broken", StatFormatter.DEFAULT),
        /** How long they spent wearing one, in ticks. */
        TIME_SPENT_RESTRAINED("time_spent_restrained", StatFormatter.TIME);

        private final String suffix;
        private final StatFormatter formatter;

        Kind(String suffix, StatFormatter formatter) {
            this.suffix = suffix;
            this.formatter = formatter;
        }

        public String suffix() {
            return suffix;
        }

        public StatFormatter formatter() {
            return formatter;
        }
    }

    /**
     * The four families that have statistics, by the id prefix Appendix A gives them.
     *
     * <p>Per-definition rather than per {@code RestraintFamily}: arm handcuffs and leg handcuffs are
     * two different counts, and one {@code HANDCUFFS} family covers both.
     */
    public enum Tracked {
        HANDCUFFS("handcuffs"),
        SHACKLES("shackles"),
        LEGCUFFS("legcuffs"),
        LEG_SHACKLES("leg_shackles");

        private final String prefix;

        Tracked(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    /** The three statistics that are not about a restraint family. */
    public static final String SUCCESSFUL_LOCKPICKS = "successful_lockpicks";
    public static final String LOCKPICKS_BROKEN = "lockpicks_broken";
    public static final String OPEN_SAFE = "open_safe";

    private CrimeStatIds() {
    }

    /** The registry path for one family and kind. */
    public static String path(Tracked tracked, Kind kind) {
        return tracked.prefix() + "_" + kind.suffix();
    }

    /** Every statistic path, in registration order. Fifteen of them. */
    public static List<String> paths() {
        List<String> paths = new ArrayList<>(15);
        for (Tracked tracked : Tracked.values()) {
            for (Kind kind : Kind.values()) {
                paths.add(path(tracked, kind));
            }
        }
        paths.add(SUCCESSFUL_LOCKPICKS);
        paths.add(LOCKPICKS_BROKEN);
        paths.add(OPEN_SAFE);
        return List.copyOf(paths);
    }

    /**
     * Which tracked family a restraint definition counts towards, or null when it counts towards
     * none.
     *
     * <p>Total over the nine definitions, and deliberately partial in its answers: the two tape
     * arm/leg definitions, the head tape, the hood and the pillory map to nothing, because Appendix A
     * declares no statistic for them. Inventing one would put an id in the registry that no resource
     * pack, advancement or server list knows about; counting them as something else would make the
     * statistics screen say something untrue.
     */
    @Nullable
    public static Tracked trackedFor(@Nullable ResourceLocation definition) {
        if (definition == null) {
            return null;
        }
        if (RestraintDefinitions.HANDCUFFS_ARMS.equals(definition)) {
            return Tracked.HANDCUFFS;
        }
        if (RestraintDefinitions.HANDCUFFS_LEGS.equals(definition)) {
            return Tracked.LEGCUFFS;
        }
        if (RestraintDefinitions.SHACKLES_ARMS.equals(definition)) {
            return Tracked.SHACKLES;
        }
        if (RestraintDefinitions.SHACKLES_LEGS.equals(definition)) {
            return Tracked.LEG_SHACKLES;
        }
        return null;
    }
}
