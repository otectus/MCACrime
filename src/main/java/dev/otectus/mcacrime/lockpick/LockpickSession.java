package dev.otectus.mcacrime.lockpick;

import dev.otectus.mcacrime.restraint.Session;
import dev.otectus.mcacrime.restraint.SessionKind;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One lockpicking attempt in progress, owned entirely by the server (M3.3).
 *
 * <p>The meter, the phase and the phase's secret target angle live here. A client is told the phase
 * and the target it is aiming at — it has to draw the ghost pick — and sends back angles. It is never
 * told, and never tells, whether it won: {@link #attempt} is the only thing that can raise the meter
 * and {@link #drain} is the only thing that can lower it, and both run here.
 *
 * <p>Parity parameters are {@link LockpickProfile}'s: start at 30, win at 40, fail at 0, and an
 * asymmetric acceptance window below and above the target. The frame-driven drain of the source is
 * reinterpreted as a per-tick drain through {@code lockpicking.drainPerTickDivisor} — documented as a
 * reinterpretation and explicitly not claimed to be frame-identical.
 *
 * <p>Meter arithmetic is in hundredths as an {@code int}. A float meter accumulating a fractional
 * drain sixty times a second is a different number on two machines; an integer one is the same number
 * everywhere, which matters because the server's answer is the only answer.
 */
public final class LockpickSession extends Session {

    /** One meter point, in the internal hundredths. */
    public static final int SCALE = 100;
    /** A full turn, in the milli-degrees the packets carry. */
    public static final int FULL_TURN_MILLI = 360_000;

    /** What one arriving attempt did. */
    public enum AttemptResult {
        /** The attempt named a phase this session is no longer on. */
        WRONG_PHASE,
        /** Too soon after the last accepted attempt. */
        TOO_SOON,
        /** Outside the acceptance window. The phase continues. */
        MISS,
        /** Inside the window; the meter rose and a new phase began. */
        HIT,
        /** Inside the window, and that was enough. */
        WIN
    }

    private final LockpickTarget target;
    private final LockpickProfile.Pick pick;
    private int meter = LockpickProfile.START_METER * SCALE;
    private int phase;
    private int phaseTargetMilliDegrees;
    private long lastAttemptTick = Long.MIN_VALUE;
    private long lastDrainTick;
    private boolean finished;

    public LockpickSession(long id, UUID actor, LockpickTarget target, long targetRevision,
                           @Nullable ResourceLocation dimension, @Nullable ResourceLocation sourceItem,
                           long expiryTick, LockpickProfile.Pick pick, int phaseTargetMilliDegrees,
                           long openedTick) {
        super(id, SessionKind.LOCKPICK, actor, target == null ? null : target.sessionTarget(),
                targetRevision, dimension, sourceItem, expiryTick);
        this.target = target;
        this.pick = pick == null ? new LockpickProfile.Pick(1, 1) : pick;
        this.phaseTargetMilliDegrees = wrap(phaseTargetMilliDegrees);
        this.lastDrainTick = openedTick;
    }

    public LockpickTarget lockTarget() {
        return target;
    }

    public LockpickProfile.Pick pick() {
        return pick;
    }

    public int phase() {
        return phase;
    }

    public int phaseTargetMilliDegrees() {
        return phaseTargetMilliDegrees;
    }

    /** The meter, in whole points, for display. */
    public int meter() {
        return meter / SCALE;
    }

    /** The meter in hundredths: what the arithmetic actually uses. */
    public int rawMeter() {
        return meter;
    }

    /** How full the meter is, 0..1, for a progress bar. */
    public float fraction() {
        return Math.max(0F, Math.min(1F, (float) meter / (LockpickProfile.WIN_METER * SCALE)));
    }

    public boolean finished() {
        return finished;
    }

    public boolean failed() {
        return meter <= LockpickProfile.FAIL_METER;
    }

    public boolean won() {
        return meter >= LockpickProfile.WIN_METER * SCALE;
    }

    /**
     * Drains the meter for the ticks that have passed since the last drain.
     *
     * <p>{@code (phase + 1) * speedIncrease / divisor} meter points per tick, which is the source's
     * own expression with its per-frame denominator replaced by a per-tick one. Later phases drain
     * faster, so a long pick gets harder rather than merely longer.
     *
     * @return true when this drain ended the session in failure
     */
    public boolean drain(long now, int divisor) {
        if (finished || now <= lastDrainTick) {
            return false;
        }
        long ticks = Math.min(now - lastDrainTick, 200L);
        lastDrainTick = now;
        int safeDivisor = Math.max(1, divisor);
        long perTick = (long) (phase + 1) * pick.speedIncrease() * SCALE / safeDivisor;
        meter -= (int) Math.min(Integer.MAX_VALUE, perTick * ticks);
        if (meter <= LockpickProfile.FAIL_METER) {
            meter = LockpickProfile.FAIL_METER;
            finished = true;
            return true;
        }
        return false;
    }

    /**
     * Scores one alignment attempt.
     *
     * <p>Every refusal reason is distinct, and none of them is "the client said so". The phase number
     * refuses a replay of the previous phase's winning angle; the interval refuses an impossible input
     * rate; the window refuses a miss. A hit raises the meter and moves to the next phase, at which
     * point the old phase's target is worthless.
     *
     * @param claimedPhase the phase the client believes it is on
     * @param angleMilliDegrees the angle it is claiming, in milli-degrees
     */
    public AttemptResult attempt(int claimedPhase, int angleMilliDegrees, long now, int minIntervalTicks,
                                 double windowBelowDegrees, double windowAboveDegrees) {
        if (finished) {
            return AttemptResult.WRONG_PHASE;
        }
        if (claimedPhase != phase) {
            return AttemptResult.WRONG_PHASE;
        }
        if (lastAttemptTick != Long.MIN_VALUE && now - lastAttemptTick < Math.max(1, minIntervalTicks)) {
            return AttemptResult.TOO_SOON;
        }
        lastAttemptTick = now;
        if (!within(angleMilliDegrees, phaseTargetMilliDegrees, windowBelowDegrees, windowAboveDegrees)) {
            return AttemptResult.MISS;
        }
        meter = Math.min(LockpickProfile.WIN_METER * SCALE, meter + pick.progressIncrease() * SCALE);
        if (meter >= LockpickProfile.WIN_METER * SCALE) {
            finished = true;
            return AttemptResult.WIN;
        }
        phase++;
        return AttemptResult.HIT;
    }

    /** Sets the next phase's secret target. Chosen by the server, never by the client. */
    public void setPhaseTarget(int milliDegrees) {
        this.phaseTargetMilliDegrees = wrap(milliDegrees);
    }

    /** Ends the session without a win: a cancel, a range loss, a swapped item. */
    public void finish() {
        finished = true;
    }

    /**
     * Whether an angle falls in the asymmetric acceptance window around a target.
     *
     * <p>Wrapping is done on the difference, not on the bounds, so a target near zero behaves exactly
     * like a target near one hundred and eighty. The source's window is ten degrees below through five
     * above; both halves are configurable here, which is the accessibility setting §8 asks for.
     */
    public static boolean within(int angleMilliDegrees, int targetMilliDegrees, double belowDegrees,
                                 double aboveDegrees) {
        int difference = wrap(angleMilliDegrees - targetMilliDegrees);
        if (difference > FULL_TURN_MILLI / 2) {
            difference -= FULL_TURN_MILLI;
        }
        double below = -Math.abs(belowDegrees) * 1000.0D;
        double above = Math.abs(aboveDegrees) * 1000.0D;
        return difference >= below && difference <= above;
    }

    /** Any angle, brought into [0, 360000). */
    public static int wrap(int milliDegrees) {
        int wrapped = milliDegrees % FULL_TURN_MILLI;
        return wrapped < 0 ? wrapped + FULL_TURN_MILLI : wrapped;
    }
}
