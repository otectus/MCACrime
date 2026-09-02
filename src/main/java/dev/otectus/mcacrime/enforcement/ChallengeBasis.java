package dev.otectus.mcacrime.enforcement;

/**
 * Whether a guard has anything to stop somebody about — the pure half of the challenge gate.
 *
 * <p>A challenge used to open on proximity alone. Nothing required the player to have any outstanding
 * case, so a guard could produce a panel reading "0 charge(s) outstanding", and asking to see the
 * charges answered "the guard checks, and finds nothing against you" — while the window kept running
 * and letting it expire still counted as refusing. Being confronted about nothing, and then penalised
 * for not answering, is the worst version of the feature.
 *
 * <p>The terms are an OR, and that is deliberate rather than lax. An actionable ledger case is itself
 * proof the law knows: an offence committed in front of the guard must be actionable immediately, not
 * once some civilian has walked over and filed a report. Requiring a filed report <em>as well</em>
 * would make crimes witnessed by the law itself unenforceable, which is the opposite of the intent —
 * so if this ever looks too permissive, the thing to tighten is what counts as an actionable case, not
 * this conjunction.
 *
 * <p>Pure and unit-testable, in the same idiom as {@link LegalTarget}'s truth table: the interesting
 * question is which combinations of facts justify an interception, and that question has nothing to do
 * with entities or levels.
 */
public final class ChallengeBasis {

    private ChallengeBasis() {
    }

    /**
     * Whether a guard may stop this player at all.
     *
     * @param actionableCases open cases the law can act on in this jurisdiction
     * @param warrant         a filed report strong enough to support an arrest ({@code reportConfidenceThreshold})
     * @param escapedPrisoner they broke out of a sentence they were serving
     * @param holdingCaptive  they are holding somebody in unlawful custody right now
     */
    public static boolean hasBasis(int actionableCases, boolean warrant,
                                   boolean escapedPrisoner, boolean holdingCaptive) {
        return actionableCases > 0 || warrant || escapedPrisoner || holdingCaptive;
    }
}
