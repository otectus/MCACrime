package dev.otectus.mcacrime.ransom;

/** Pure ransom-amount math (spec §8.5), mirroring {@code FineCalculator}: base × the payer-tier multiplier, clamped ≥ 0. */
public final class RansomCalculator {

    private RansomCalculator() {
    }

    public static long amount(long base, double tierMultiplier) {
        return Math.max(0L, Math.round(Math.max(0L, base) * Math.max(0.0, tierMultiplier)));
    }
}
