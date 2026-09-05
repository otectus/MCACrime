package dev.otectus.mcacrime;

import dev.otectus.mcacrime.bounty.BountyCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounty price, which the spec insists must be a function of persisted wrongdoing rather than of
 * the kill. These tests are therefore mostly about what <em>moves</em> the number and what cannot.
 */
class BountyCalculatorTest {

    /** The shipped defaults, so a row that changes here is a row that changes in a real world. */
    private static long defaults(int unresolvedCount, long heatSum, long fines, int priorWarrants) {
        return BountyCalculator.compute(8L, unresolvedCount, heatSum, 2.0D, fines, 0.25D,
                priorWarrants, 4L, 1L, 128L);
    }

    @Test
    void aFreshlyWantedOutlawIsWorthTheBase() {
        assertEquals(8L, defaults(0, 0L, 0L, 0));
    }

    @Test
    void unresolvedHeatRaisesThePrice() {
        assertTrue(defaults(1, 10L, 0L, 0) > defaults(1, 0L, 0L, 0),
                "a worse record has to be worth more, or the ledger is decoration");
        // base 8 + one case (no extra) + 10 heat * 2.0
        assertEquals(28L, defaults(1, 10L, 0L, 0));
    }

    @Test
    void outstandingFinesAreSharedNotPaidInFull() {
        // base 8 + 40 * 0.25
        assertEquals(18L, defaults(1, 0L, 40L, 0));
    }

    @Test
    void repeatOffendersCostMorePerClosedWarrant() {
        assertEquals(8L, defaults(0, 0L, 0L, 0));
        assertEquals(16L, defaults(0, 0L, 0L, 2));
    }

    @Test
    void everyPriceIsClamped() {
        assertEquals(128L, defaults(20, 10_000L, 10_000L, 50), "the ceiling is a ceiling");
        assertEquals(1L, BountyCalculator.compute(0L, 0, 0L, 0.0D, 0L, 0.0D, 0, 0L, 1L, 128L),
                "the floor keeps a bounty worth hunting");
    }

    @Test
    void aZeroCeilingPaysNothingHoweverBadTheyAre() {
        assertEquals(0L, BountyCalculator.compute(8L, 9, 900L, 2.0D, 900L, 1.0D, 9, 4L, 0L, 0L));
    }

    @Test
    void negativeAndNonsenseInputsNeverSubtractFromThePrice() {
        // A hand-edited config or a corrupt record must not be able to pay a hunter backwards.
        assertEquals(8L, BountyCalculator.compute(8L, -5, -100L, -1.0D, -100L, -1.0D, -3, -4L, 0L, 128L));
        assertEquals(8L, BountyCalculator.compute(8L, 0, 0L, Double.NaN, 40L, Double.NaN, 0, 0L, 0L, 128L));
    }

    @Test
    void aFloorAboveTheCeilingCollapsesToTheFloorRatherThanInverting() {
        assertEquals(50L, BountyCalculator.compute(8L, 0, 0L, 0.0D, 0L, 0.0D, 0, 0L, 50L, 10L));
    }
}
