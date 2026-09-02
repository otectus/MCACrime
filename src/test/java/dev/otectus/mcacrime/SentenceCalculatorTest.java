package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.SentenceCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sentence math, mirroring {@code FineCalculatorTest}.
 *
 * <p>The properties worth pinning are the ones a player would notice going wrong: a sentence is never
 * zero (a term that ends the instant it starts is indistinguishable from a bug), never longer than the
 * configured ceiling, and never shorter for having done more.
 */
class SentenceCalculatorTest {

    private static final int BASE = 1200;
    private static final int PER_HEAT = 30;
    private static final int PER_CHARGE = 200;
    private static final double BLUE = 0.5;
    private static final long MAX = 72_000L;

    private static long sentence(long heat, Band band, int charges) {
        return SentenceCalculator.sentenceFor(heat, band, charges, BASE, PER_HEAT, PER_CHARGE, BLUE, MAX);
    }

    @Test
    void addsBaseHeatAndCharges() {
        assertEquals(BASE + 40 * PER_HEAT + 3 * PER_CHARGE, sentence(40, Band.GREY, 3));
    }

    @Test
    void blueOffendersServeTheReducedShareTheyWouldPay() {
        long grey = sentence(40, Band.GREY, 3);
        assertEquals(Math.round(grey * BLUE), sentence(40, Band.BLUE, 3));
    }

    @Test
    void redOffendersGetNoDiscount() {
        assertEquals(sentence(40, Band.GREY, 3), sentence(40, Band.RED, 3));
    }

    @Test
    void neverZeroEvenWithEverythingAtNothing() {
        assertEquals(1L, SentenceCalculator.sentenceFor(0, Band.GREY, 0, 0, 0, 0, 1.0, MAX));
    }

    @Test
    void clampedToTheCeiling() {
        assertEquals(MAX, sentence(1_000_000L, Band.RED, 500));
    }

    /** A hand-edited or corrupted Heat must saturate at the ceiling, never overflow to a short sentence. */
    @Test
    void absurdHeatSaturatesRatherThanWrappingNegative() {
        assertEquals(MAX, sentence(Long.MAX_VALUE, Band.GREY, 0));
    }

    @Test
    void monotonicInHeatAndInCharges() {
        assertTrue(sentence(50, Band.GREY, 1) > sentence(10, Band.GREY, 1));
        assertTrue(sentence(10, Band.GREY, 5) > sentence(10, Band.GREY, 1));
    }

    // ---------------------------------------------------------------- the surrender waiver

    /**
     * The waiver used to be applied by writing into the live sentence, after which the arrest took the
     * maximum of the old and new lengths and put the whole quarter back. Folding it into the number
     * before the sentence is ever stored is what makes surrendering actually cheaper.
     */
    @Test
    void surrenderLeavesTheConfiguredShareOfTheSentence() {
        assertEquals(750L, SentenceCalculator.afterSurrender(1000L, 25));
        assertEquals(500L, SentenceCalculator.afterSurrender(1000L, 50));
        assertEquals(1000L, SentenceCalculator.afterSurrender(1000L, 0));
    }

    @Test
    void aFullWaiverStillLeavesASentenceToServe() {
        assertEquals(1L, SentenceCalculator.afterSurrender(1000L, 100),
                "a sentence of zero ticks would be released on the tick it started");
        assertEquals(1L, SentenceCalculator.afterSurrender(0L, 25));
        assertEquals(1L, SentenceCalculator.afterSurrender(-50L, 25));
    }

    @Test
    void anOutOfRangePercentageIsClampedRatherThanTrusted() {
        assertEquals(1000L, SentenceCalculator.afterSurrender(1000L, -10));
        assertEquals(1L, SentenceCalculator.afterSurrender(1000L, 500));
    }

    @Test
    void theWaiverNeverLengthensASentence() {
        for (int pct = 0; pct <= 100; pct++) {
            long kept = SentenceCalculator.afterSurrender(2400L, pct);
            assertTrue(kept <= 2400L, "pct " + pct + " produced " + kept);
            assertTrue(kept >= 1L);
        }
    }
}
