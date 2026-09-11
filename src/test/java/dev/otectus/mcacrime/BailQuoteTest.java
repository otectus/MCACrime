package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.BailQuote;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What bail costs.
 *
 * <p>Priced on time remaining, so a family arriving at the end of a sentence is not quoted the
 * day-one price, and compounded per prior arrest, so bail stays relief rather than a fee schedule for
 * a household that treats it as one. The clamps are what stop either of those becoming absurd.
 */
class BailQuoteTest {

    /** The shipped defaults: 64 flat, 8 per thousand ticks, 16..4096, x1.5 per prior arrest. */
    private static final BailQuote.Settings DEFAULTS =
            new BailQuote.Settings(64, 8.0D, 16, 4096, 1.5D);

    @Test
    void aSentenceWithNothingLeftStillCostsTheFlatFee() {
        assertEquals(64L, BailQuote.cost(0L, 0, DEFAULTS));
    }

    @Test
    void thePriceRisesWithTheTimeStillToServe() {
        // 64 + 8 * (6000/1000) = 112
        assertEquals(112L, BailQuote.cost(6000L, 0, DEFAULTS));
        // 64 + 8 * 2.4 = 83.2 -> 83
        assertEquals(83L, BailQuote.cost(2400L, 0, DEFAULTS));
    }

    @Test
    void negativeRemainingTicksAreTreatedAsNone() {
        assertEquals(BailQuote.cost(0L, 0, DEFAULTS), BailQuote.cost(-5000L, 0, DEFAULTS));
    }

    @Test
    void eachPriorArrestCompoundsThePrice() {
        assertEquals(112L, BailQuote.cost(6000L, 0, DEFAULTS));
        assertEquals(168L, BailQuote.cost(6000L, 1, DEFAULTS));
        assertEquals(252L, BailQuote.cost(6000L, 2, DEFAULTS));
    }

    @Test
    void theMinimumAndMaximumBothBite() {
        BailQuote.Settings cheap = new BailQuote.Settings(0, 0.0D, 16, 4096, 1.5D);
        assertEquals(16L, BailQuote.cost(0L, 0, cheap));
        assertEquals(16L, BailQuote.cost(200_000L, 0, cheap));

        // Twenty prior arrests would run to millions; the ceiling is what makes that a number.
        assertEquals(4096L, BailQuote.cost(6000L, 20, DEFAULTS));
    }

    @Test
    void aMultiplierOfOneNeverCompounds() {
        BailQuote.Settings flat = new BailQuote.Settings(64, 8.0D, 16, 4096, 1.0D);
        assertEquals(BailQuote.cost(6000L, 0, flat), BailQuote.cost(6000L, 9, flat));
    }

    @Test
    void aNegativeArrestCountCannotDiscountAnybody() {
        assertEquals(BailQuote.cost(6000L, 0, DEFAULTS), BailQuote.cost(6000L, -3, DEFAULTS));
    }

    @Test
    void theReleaseConditionSaysWhichOneItIs() {
        assertEquals("mcacrime.bail.condition.sentence", BailQuote.releaseConditionKey(1L));
        assertEquals("mcacrime.bail.condition.imminent", BailQuote.releaseConditionKey(0L));
    }

    @Test
    void aQuoteNormalisesRatherThanCarryingNulls() {
        BailQuote.Quote quote = new BailQuote.Quote(-5L, null, null, -1L, null);
        assertEquals(0L, quote.cost());
        assertEquals(0L, quote.remainingTicks());
        assertEquals("", quote.relativeName());
        assertEquals("", quote.offenceKey());
        assertEquals("", quote.releaseConditionKey());
    }
}
