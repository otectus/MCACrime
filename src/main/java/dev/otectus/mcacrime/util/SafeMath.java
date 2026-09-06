package dev.otectus.mcacrime.util;

/**
 * Arithmetic that saturates instead of wrapping (0.6.0, audit finding B32).
 *
 * <p>Every number this mod carries is a quantity a player can push on: Heat accumulates, a base price
 * comes out of a datapack, a bounty multiplier comes out of a config file somebody edited by hand. A
 * {@code long} that overflows on the way through one of those does not fail loudly — it comes out
 * negative, and a negative price pays the player to be arrested. Saturating at
 * {@link Long#MAX_VALUE} is wrong too, but it is wrong in the direction that reads as "absurdly
 * expensive" rather than as free money, and every consumer already clamps to a configured ceiling.
 *
 * <p>Nothing here changes a result that was already in range. The methods are drop-in replacements
 * for {@code +}, {@code *} and a {@code long} clamp, which is why they are applied at the operator
 * rather than at the end of a formula: the wrap happens mid-expression, and a check afterwards has
 * already lost the evidence.
 *
 * <p>{@code Mth.clamp} does have a {@code long} overload on 1.21.1, and this class still does not use
 * it. Vanilla's answer for a mis-ordered range is {@code max}; the answer every call site here was
 * written against is {@code min}, and those call sites are shared logic with the 1.20.1 codebase, so
 * the two must agree. The saturating siblings have no vanilla equivalent at all.
 */
public final class SafeMath {

    private SafeMath() {
    }

    /** {@code a + b}, saturating at the {@code long} bounds rather than wrapping. */
    public static long addSat(long a, long b) {
        long sum = a + b;
        // Overflow iff the operands share a sign that the result does not.
        if (((a ^ sum) & (b ^ sum)) < 0L) {
            return a > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return sum;
    }

    /** {@code a * b}, saturating at the {@code long} bounds rather than wrapping. */
    public static long mulSat(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            // The sign of the true product, which is what decides which end it saturates at.
            return ((a ^ b) < 0L) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /**
     * {@code value * factor}, rounded, saturating, and refusing a factor that is not a number.
     *
     * <p>A NaN or infinite factor is treated as zero rather than propagated: it can only come from a
     * hand-edited config or a divide a datapack author did not mean to do, and one poisoned key must
     * not turn every price in the world into {@code NaN}.
     */
    public static long mulSat(long value, double factor) {
        if (!Double.isFinite(factor)) {
            return 0L;
        }
        double product = (double) value * factor;
        if (!Double.isFinite(product)) {
            return product > 0.0D ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        if (product >= (double) Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (product <= (double) Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        return Math.round(product);
    }

    /** Clamps {@code value} to {@code [min, max]}; a mis-ordered range collapses to {@code min}. */
    public static long clampLong(long value, long min, long max) {
        if (min > max) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** {@code value} when it is a real number, {@code fallback} when it is NaN or infinite. */
    public static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
