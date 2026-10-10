package dev.otectus.mcacrime.job;

/**
 * Whether a villager is still a blank slate an automatic route may turn into a criminal (0.7.7).
 *
 * <p>Pure, like {@link CriminalJobAssigner}, because the rule is the whole fix. Before 0.7.7 only the
 * settlement thief route asked whether a candidate was employed; the wild route and the fence route
 * asked nothing, and no route looked at trading history at all. A villager with no MCA home village
 * counts as wild, which is exactly what a player's trading hall is, so a master librarian standing in
 * one could be relabelled a Thief and lose every trade it had: the occupation transaction clears
 * offers, and its rollback snapshot lives only as long as the transaction does.
 *
 * <p>Eligible means all four facts at once: a profession that could be read, which is
 * {@code none}, no trading XP, level one, and no offer that has ever been used. Anything else is a
 * villager somebody has invested in, and only an explicit operator or API request may change it.
 *
 * <p>The checks run in the order an operator needs to hear them: MCA's own protection first, an
 * unreadable profession next (fail closed, never "assume unemployed"), then employment, then trade
 * history.
 */
public final class RecruitmentEligibility {

    private RecruitmentEligibility() {
    }

    /** The answer, named so the caller can report why rather than merely refuse. */
    public enum Verdict {
        /** A readable {@code none} profession with no trading history. */
        ELIGIBLE,
        /** The villager holds another profession. */
        EMPLOYED,
        /** Unemployed, but with trading XP, a level above one, or a used offer. */
        TRADED,
        /** MCA marks the profession as one it must not lose. */
        PROTECTED,
        /** The profession or the trade state could not be read, which is never permission. */
        UNREADABLE;

        public boolean eligible() {
            return this == ELIGIBLE;
        }

        public boolean rejected() {
            return this != ELIGIBLE;
        }
    }

    /**
     * Everything the decision needs about one villager, read without side effects.
     *
     * @param readable     the profession and the trade state could both be read
     * @param unemployed   the profession is {@code none}
     * @param protectedNpc MCA's "do not overwrite this profession" flag
     * @param tradingXp    vanilla trading XP
     * @param level        vanilla villager level, 1 to 5
     * @param offerUsed    some initialised offer has been used at least once. Read from the raw field,
     *                     because asking {@code getOffers()} would create the trades being asked about
     */
    public record Facts(boolean readable, boolean unemployed, boolean protectedNpc, int tradingXp,
                        int level, boolean offerUsed) {

        /** The honest answer when nothing could be read. */
        public static Facts unreadable() {
            return new Facts(false, false, false, 0, 1, false);
        }
    }

    public static Verdict evaluate(Facts facts) {
        if (facts == null) {
            return Verdict.UNREADABLE;
        }
        if (facts.protectedNpc()) {
            return Verdict.PROTECTED;
        }
        if (!facts.readable()) {
            return Verdict.UNREADABLE;
        }
        if (!facts.unemployed()) {
            return Verdict.EMPLOYED;
        }
        if (facts.tradingXp() > 0 || facts.level() > 1 || facts.offerUsed()) {
            return Verdict.TRADED;
        }
        return Verdict.ELIGIBLE;
    }
}
