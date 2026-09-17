package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;

/**
 * Whether a villager is doing a job a settlement needs more than it needs another guard.
 *
 * <h2>Why MCA: Crime has to ask</h2>
 *
 * <p>{@code GuardPopulationService} tops a village up to its guard share by converting ordinary
 * villagers. Without a settlement mod that is nearly free: an unemployed villager becomes a guard and
 * the village is safer. With Townstead it is not. Townstead villagers hold real occupations that
 * produce real goods on real shifts, and MCA's guard conversion overwrites the profession — so an
 * automatic pass can quietly dismantle a workshop the player spent an evening building, one villager
 * per cooldown, with no message anywhere saying so.
 *
 * <h2>Not knowing is an answer, and it is not "no"</h2>
 *
 * <p>The tempting shortcut is to treat an unreadable role as "not a worker" and convert. That is
 * exactly backwards: the case where MCA: Crime cannot read Townstead's state is the case where it is
 * most likely to be destroying something, because it is precisely the villagers a partially-bound
 * bridge cannot describe who might be the ones on shift. So {@link Stance#UNKNOWN} exists as a
 * first-class answer and {@code GuardPopulationService} stops converting in that village rather than
 * guessing — a village briefly short of guards is recoverable, a village whose baker was drafted is
 * not.
 *
 * <p>Both capabilities are required before any answer counts as known, which is the same pair
 * {@code TownsteadDiagnostics} lists for the {@code protectWorkerAssignments} switch. Half of it would
 * be worse than none: a schedule with no profession cannot tell an off-shift baker from an unemployed
 * villager, and a profession with no schedule cannot tell a working one from one who is asleep.
 */
public final class TownsteadRolePolicy {

    /** What is known about one villager's Townstead role. */
    public enum Stance {

        /** The operator turned the protection off. Nobody is protected and nothing is halted. */
        NOT_ENFORCED,

        /** Townstead could not describe this villager. Automatic recruitment stops for their village. */
        UNKNOWN,

        /** They hold a Townstead occupation, or are on shift right now. Do not draft them. */
        PROTECTED,

        /** Townstead knows them and they are neither. They may be drafted. */
        AVAILABLE
    }

    /** One villager's role, with the sentence an operator reads when it blocks something. */
    public record Role(Stance stance, String reason) {

        public Role {
            reason = reason == null ? "" : reason;
        }

        /** True only for a villager Townstead says is working or employed. */
        public boolean protectedWorker() {
            return stance == Stance.PROTECTED;
        }

        /**
         * Whether Townstead actually answered.
         *
         * <p>{@link Stance#NOT_ENFORCED} counts as known: the operator decided, and a decision is not
         * a gap in information. What must never count is {@link Stance#UNKNOWN}.
         */
        public boolean roleKnown() {
            return stance != Stance.UNKNOWN;
        }

        /** The one state in which a caller must stop rather than proceed. */
        public boolean halts() {
            return stance == Stance.UNKNOWN;
        }
    }

    private static final Role NOT_ENFORCED =
            new Role(Stance.NOT_ENFORCED, "protectWorkerAssignments is off");

    private static final Role NO_TOWNSTEAD =
            new Role(Stance.NOT_ENFORCED, "Townstead is not part of this world");

    private TownsteadRolePolicy() {
    }

    /**
     * Whether there is a settlement mod here whose opinion could be withheld.
     *
     * <p>The question the rest of this class rests on, and the one it used to skip. {@code ABSENT}
     * means Townstead is not installed; {@code OFF} means it is installed and an operator switched the
     * integration off. In both, nobody holds a Townstead occupation, nobody is on a Townstead shift,
     * and there is no information being withheld — so there is nothing for this protection to protect
     * and nothing for {@code GuardPopulationService} to halt on.
     *
     * <p>Getting this wrong was not a cosmetic bug. Without it, every villager on a server with no
     * Townstead classified as {@link Stance#UNKNOWN} the moment the two config switches were on,
     * {@code GuardPopulationService.assess} set {@code halted}, and automatic guard recruitment stopped
     * in every village in the world — the failure mode being reported as "villages never get guards
     * any more", with nothing anywhere saying why.
     */
    public static boolean townsteadPresent() {
        try {
            TownsteadBridge.State state = TownsteadBridge.state();
            return state != TownsteadBridge.State.ABSENT && state != TownsteadBridge.State.OFF;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether the protection is switched on at all.
     *
     * <p>Three halves now, not two: Townstead has to be here, the integration has to be on, and the
     * switch has to be on. Wrapped because a dedicated CLI has no config, and off is the answer that
     * leaves guard recruitment exactly as it behaved before Townstead existed.
     */
    public static boolean enforced() {
        if (!townsteadPresent()) {
            return false;
        }
        try {
            return McaCrimeConfig.COMMON.townsteadEnabled.get()
                    && McaCrimeConfig.COMMON.townsteadProtectWorkerAssignments.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * What Townstead says about this villager.
     *
     * <p>Capability first, entity second. Asking the bridge about a villager when the two reads this
     * decision needs have not bound would produce a view whose profession is an empty string — which
     * reads identically to "unemployed", and would convert exactly the villagers that most need
     * protecting.
     */
    public static Role of(@Nullable Entity villager) {
        if (!townsteadPresent()) {
            // Not "we could not ask" — there is nobody to ask. Recruitment behaves exactly as it did
            // before this class existed, which is the only correct answer on the overwhelming majority
            // of installs.
            return NO_TOWNSTEAD;
        }
        if (!enforced()) {
            return NOT_ENFORCED;
        }
        if (villager == null) {
            return new Role(Stance.UNKNOWN, "no entity to ask about");
        }
        boolean scheduleReadable = TownsteadBridge.has(TownsteadCapability.READ_SCHEDULE);
        boolean professionReadable = TownsteadBridge.has(TownsteadCapability.READ_PROFESSION);
        if (!scheduleReadable || !professionReadable) {
            return new Role(Stance.UNKNOWN, "Townstead role reads are unavailable ("
                    + TownsteadCapability.READ_SCHEDULE.id() + "=" + scheduleReadable + ", "
                    + TownsteadCapability.READ_PROFESSION.id() + "=" + professionReadable + ")");
        }
        TownsteadQueryResult<TownsteadVillagerView> result = TownsteadBridge.villager(villager);
        TownsteadVillagerView view = result.orElse(null);
        if (view == null) {
            return new Role(Stance.UNKNOWN, result.describe());
        }
        return decide(true, view.schedule().currentActivity(), true, view.professionId());
    }

    /**
     * The rule itself, with every lookup already done.
     *
     * <p>Two independent grounds for protection, and they catch different villagers. A Townstead
     * profession means the settlement is counting on this villager's output whatever the clock says,
     * and converting them destroys it. An active work shift catches the villager who is producing
     * right now under whatever arrangement Townstead is using, including one this mod has no name for.
     *
     * @param scheduleReadable   whether the schedule read bound
     * @param currentActivity    Townstead's own shift name, lowercased at the seam
     * @param professionReadable whether the profession read bound
     * @param professionId       Townstead's profession id, blank for none
     */
    public static Role decide(boolean scheduleReadable, @Nullable String currentActivity,
                              boolean professionReadable, @Nullable String professionId) {
        if (!scheduleReadable || !professionReadable) {
            return new Role(Stance.UNKNOWN, "Townstead role reads are unavailable");
        }
        String activity = currentActivity == null ? "" : currentActivity.toLowerCase(Locale.ROOT);
        String profession = professionId == null ? "" : professionId.trim();
        if (!profession.isEmpty()) {
            return new Role(Stance.PROTECTED, "holds the Townstead occupation " + profession);
        }
        if (TownsteadScheduleView.ACTIVITY_WORK.equals(activity)) {
            return new Role(Stance.PROTECTED, "is on a Townstead work shift");
        }
        return new Role(Stance.AVAILABLE, "no Townstead occupation and not on shift");
    }
}
