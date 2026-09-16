package dev.otectus.mcacrime.effect;

/**
 * The rule that stops sand from being a stun-lock, as a pure function of two timestamps (§13.6).
 *
 * <p>Per-attacker protection was never enough: two players alternating bottles would each be within
 * their own cooldown and the target would never see again. So the window belongs to the <em>target</em>
 * and it applies against every thrower, which is the only shape of the rule that closes SAND-09.
 *
 * <p>Both timestamps are overworld game time. One clock for every dimension is what makes a target
 * that walked through a portal mid-effect still recover on schedule instead of inheriting whatever
 * tick count the destination happened to be on.
 */
public final class SandRecovery {

    private SandRecovery() {
    }

    /**
     * What is known about one target.
     *
     * @param activeUntil   the tick the current sand effect runs out; 0 when none is active
     * @param recoveryUntil the tick until which sand cannot take hold again; 0 when clear
     */
    public record State(long activeUntil, long recoveryUntil) {

        public static final State CLEAR = new State(0L, 0L);

        public boolean isClear() {
            return activeUntil <= 0L && recoveryUntil <= 0L;
        }

        /** The later of the two, which is when this record can be forgotten entirely. */
        public long expiresAt() {
            return Math.max(activeUntil, recoveryUntil);
        }
    }

    /** Whether a new bottle may take hold now: no active sand, and no recovery window open. */
    public static boolean canApply(State state, long now) {
        State reconciled = reconcile(state, now);
        return now >= reconciled.activeUntil() && now >= reconciled.recoveryUntil();
    }

    /**
     * The state after an application lands.
     *
     * <p>The recovery window is scheduled from the moment the effect is due to end, not from now, so
     * the protection is genuinely "after the blindness" rather than overlapping most of it.
     */
    public static State applied(long now, int durationTicks, int recoveryTicks) {
        long ends = now + Math.max(0, durationTicks);
        return new State(ends, ends + Math.max(0, recoveryTicks));
    }

    /**
     * The state after the effect ended early — cured with milk, cleared by a command, or removed by
     * another mod. The recovery window still runs its full length from the moment sight came back,
     * because an early cure must not be a way to shorten the protection into a re-blind loophole.
     */
    public static State cured(long now, int recoveryTicks) {
        return new State(now, now + Math.max(0, recoveryTicks));
    }

    /**
     * Reads a stored record back on load.
     *
     * <p>Two things go wrong across a save. A timestamp from a world whose time was set backwards is
     * arbitrarily far in the future and would blind the target forever, so anything more than a full
     * recovery window ahead of now is treated as stale and dropped. Anything already in the past is
     * simply clear. Neither case is an error worth logging; both are "sand is over".
     */
    public static State reconcile(State stored, long now) {
        if (stored == null) {
            return State.CLEAR;
        }
        long horizon = now + MAX_PLAUSIBLE_AHEAD;
        long active = stored.activeUntil() > horizon ? 0L : stored.activeUntil();
        long recovery = stored.recoveryUntil() > horizon ? 0L : stored.recoveryUntil();
        if (active <= now && recovery <= now) {
            return State.CLEAR;
        }
        return new State(active, recovery);
    }

    /** Whether a stored record has nothing left to say and can be dropped from the ledger. */
    public static boolean forgettable(State state, long now) {
        return reconcile(state, now).isClear();
    }

    /**
     * The furthest ahead a legitimate record can sit: the longest effect this mod can apply plus the
     * longest recovery it can configure, with room to spare. Beyond that the clock moved, not the sand.
     */
    private static final long MAX_PLAUSIBLE_AHEAD = 200L + 20L * 60L * 60L;
}
