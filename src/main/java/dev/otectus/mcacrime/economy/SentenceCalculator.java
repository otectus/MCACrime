package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.crime.Band;

/**
 * Pure sentence math: how long a jail term runs, in online ticks.
 *
 * <p>The sibling of {@link FineCalculator}, and deliberately its mirror image. A fine is what you pay
 * when the charges are light enough to be paid off; a sentence is what you serve when they are not, and
 * until now the mod had no answer to "how long" at all — {@code JailService.jail} took a tick count and
 * the only caller that ever supplied one was the operator command. Surrendering could not produce a
 * sentence because nothing could work out what it should be.
 *
 * <p>Three terms, all configurable: a fixed base so even a trivial arrest costs something, a term per
 * point of Heat so a rampage is answered for, and a term per outstanding charge so a long ledger is
 * worse than one bad afternoon. Blue offenders serve the same reduced share they would pay
 * ({@code blueFineMultiplier}), because a first-offender discount that applies to money and not to time
 * would push every well-liked offender toward the cell.
 *
 * <p>Unit-testable: static, no game or config dependencies, and the clamp is applied here rather than
 * left to the caller so the contract "never zero, never longer than the ceiling" holds everywhere.
 */
public final class SentenceCalculator {

    private SentenceCalculator() {
    }

    /**
     * The sentence for an offender, clamped to {@code [1, maxTicks]}.
     *
     * @param heat            Heat at the moment of arrest
     * @param band            the offender's standing
     * @param chargeCount     how many actionable cases are being answered for
     * @param baseTicks       fixed part of every sentence
     * @param ticksPerHeat    added per point of Heat
     * @param ticksPerCharge  added per outstanding charge
     * @param blueMultiplier  the share of a sentence a Blue offender serves
     * @param maxTicks        the hard ceiling ({@code maxJailCommandTicks})
     */
    public static long sentenceFor(long heat, Band band, int chargeCount, int baseTicks,
                                   int ticksPerHeat, int ticksPerCharge, double blueMultiplier,
                                   long maxTicks) {
        long safeHeat = Math.max(0L, heat);
        long safeCharges = Math.max(0, chargeCount);
        // Saturating: a hand-edited Heat of Long.MAX_VALUE must produce the ceiling, not a negative
        // sentence that JailService would then clamp up to a single tick.
        long total = saturatingAdd(baseTicks,
                saturatingAdd(saturatingMultiply(safeHeat, ticksPerHeat),
                        saturatingMultiply(safeCharges, ticksPerCharge)));
        if (band == Band.BLUE) {
            total = Math.round(total * Math.max(0.0, blueMultiplier));
        }
        long ceiling = Math.max(1L, maxTicks);
        return Math.max(1L, Math.min(total, ceiling));
    }

    /**
     * The share of a sentence a voluntary surrender leaves, never below a single tick.
     *
     * <p>The waiver used to be applied by {@code SurrenderService} writing directly into the live
     * {@code JailState}, which made it a second writer of a record {@code JailService} owns -- and the
     * arrest that followed then took the maximum of the old and new lengths and silently put the
     * quarter back. Computing it here, before the sentence is ever stored, means the discount is part
     * of the number rather than an edit to it.
     *
     * @param sentenceTicks the sentence as assessed
     * @param reductionPct  percent waived, clamped to [0, 100]
     */
    public static long afterSurrender(long sentenceTicks, int reductionPct) {
        if (sentenceTicks <= 0L) {
            return 1L;
        }
        int pct = Math.max(0, Math.min(100, reductionPct));
        long kept = Math.round(sentenceTicks * ((100 - pct) / 100.0));
        return Math.max(1L, kept);
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    private static long saturatingMultiply(long a, long b) {
        if (a == 0L || b == 0L) {
            return 0L;
        }
        long product = a * b;
        return product / b != a ? Long.MAX_VALUE : product;
    }
}
