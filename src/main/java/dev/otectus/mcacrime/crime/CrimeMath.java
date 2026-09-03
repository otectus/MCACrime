package dev.otectus.mcacrime.crime;

/**
 * Pure karma/heat arithmetic — clamping, online-tick decay accounting, and the Wanted test. Kept free
 * of any Minecraft/Forge dependency so the rules in spec §3 are unit-testable without a running game
 * (spec §18). {@link dev.otectus.mcacrime.engine.CrimeState} and
 * {@link dev.otectus.mcacrime.engine.CrimeDecayHandler} are the only callers.
 */
public final class CrimeMath {

    private CrimeMath() {
    }

    /** Saturating clamp of {@code value} to {@code [min, max]} (spec §20 "clamp deltas"). */
    public static long clamp(long value, long min, long max) {
        if (min > max) {
            // Defensive: a mis-ordered range collapses to the lower bound rather than throwing.
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** True when {@code heat} has reached the Wanted threshold (spec §1.2). */
    public static boolean isWanted(long heat, long wantedThreshold) {
        return heat >= wantedThreshold;
    }

    /**
     * How many whole decay steps have elapsed since {@code lastDecayTick}, given the player's monotonic
     * {@code onlineTicksLived} and a step {@code period} in ticks. Returns 0 for a non-positive period
     * or when no full step has elapsed. Because the clock is online-ticks (never wall-clock), a
     * logged-out player accrues no steps — logout pauses decay by construction (spec §7.1).
     */
    public static long stepsElapsed(long onlineTicksLived, long lastDecayTick, long period) {
        if (period <= 0L) {
            return 0L;
        }
        long elapsed = onlineTicksLived - lastDecayTick;
        if (elapsed < period) {
            return 0L;
        }
        return elapsed / period;
    }

    /**
     * Moves {@code value} toward zero by {@code amountPerStep * steps}, never crossing zero (a positive
     * value never goes negative and vice-versa). Used for the gentle ±1/MC-day karma normalisation and
     * per-minute heat bleed-off (spec §3.1).
     */
    public static long decayTowardZero(long value, long amountPerStep, long steps) {
        if (value == 0L || amountPerStep <= 0L || steps <= 0L) {
            return value;
        }
        long magnitude = Math.abs(amountPerStep) * steps;
        if (value > 0L) {
            return Math.max(0L, value - magnitude);
        }
        return Math.min(0L, value + magnitude);
    }
}
