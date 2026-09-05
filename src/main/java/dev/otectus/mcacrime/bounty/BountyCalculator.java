package dev.otectus.mcacrime.bounty;

/**
 * What an outlaw is worth (0.5.1). Pure arithmetic over facts the ledger already holds.
 *
 * <p>The spec is explicit that this must not be a kill reward: a bounty is priced off persisted
 * wrongdoing, so a player who has done nothing but cross the Wanted threshold is worth the base and
 * no more, and a player with a page of unresolved cases and unpaid fines is worth considerably more.
 * That also means the number is honest in the other direction — settle your cases and the price on
 * your head falls, which is the whole point of having fines at all.
 *
 * <pre>
 * bounty = base
 *        + unresolvedSeverity   (count and accumulated Heat, scaled)
 *        + outstandingFineShare (a cut of what is owed)
 *        + repeatOffenderBonus  (per previously closed warrant)
 * clamped to [min, max]
 * </pre>
 */
public final class BountyCalculator {

    private BountyCalculator() {
    }

    /**
     * The clamped principal reward.
     *
     * <p>Accumulated in {@code double} rather than {@code long} for one reason: a pathological config
     * (maximum scale, a huge fine share, a career criminal) must produce a very large number that then
     * clamps to {@code max}, not a wrapped negative one that pays the hunter backwards. Every negative
     * input is floored at zero first, so no term can quietly subtract from the price either.
     *
     * @param severityScale  multiplier on accumulated unresolved Heat
     * @param fineShare      fraction of outstanding fines folded into the price, 0..1
     * @param repeatBonus    added once per previously closed warrant
     */
    public static long compute(long baseBounty, int unresolvedCount, long unresolvedHeatSum,
                               double severityScale, long outstandingFines, double fineShare,
                               int priorWarrants, long repeatBonus, long min, long max) {
        double severity = Math.max(0L, unresolvedHeatSum) * nonNegative(severityScale);
        double fines = Math.max(0L, outstandingFines) * Math.min(1.0D, nonNegative(fineShare));
        double repeats = (double) Math.max(0, priorWarrants) * Math.max(0L, repeatBonus);
        // The case count is worth the base again per case beyond the first: two separate robberies are
        // a worse outstanding record than one robbery of twice the value, and Heat alone cannot say so.
        double count = (double) Math.max(0, unresolvedCount - 1) * Math.max(0L, baseBounty);

        double total = Math.max(0L, baseBounty) + severity + fines + repeats + count;
        long floor = Math.max(0L, min);
        long ceiling = Math.max(floor, max);
        if (total <= floor) {
            return floor;
        }
        if (total >= ceiling) {
            return ceiling;
        }
        return Math.round(total);
    }

    /** Zero for anything negative or not a number, so a hand-edited config cannot poison the sum. */
    private static double nonNegative(double value) {
        return Double.isNaN(value) || value <= 0.0D ? 0.0D : value;
    }
}
