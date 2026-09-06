package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.fence.FencePolicy;
import dev.otectus.mcacrime.economy.fence.FencePricing;
import dev.otectus.mcacrime.economy.fence.FencePricing.PricingInputs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arbitrage bound, swept rather than sampled (0.6.0, T16, audit finding B08).
 *
 * <p>{@code FencePricingTest} already asserts {@code buy < sell} at a handful of points. That was not
 * the hole. The hole was that the buy price <em>derived</em> from the marked-up sale price, so Heat
 * raised both together: buy an item while cool, become Wanted, sell it back at the surcharged rate and
 * the difference is free money that scales with how criminal the player is willing to be. Closing it
 * needs two properties at once, and both are swept across the whole of each modifier's config range
 * rather than spot-checked, because one combination that meets is a farm.
 *
 * <ol>
 *   <li>{@code buy < sell} for every combination of Karma, Heat and Wanted.</li>
 *   <li>The buy price never <em>rises</em> with Heat. Risk is a reduction to what the fence will pay
 *       and nothing else, so no amount of attracting attention can improve what an item sells back
 *       for.</li>
 * </ol>
 */
class FencePricingArbitrageTest {

    private static final FencePolicy POLICY = FencePolicy.defaults();
    /** The shipped {@code bands.karmaRedThreshold} and {@code bands.wantedHeatThreshold}. */
    private static final long RED = -100L;
    private static final long WANTED_HEAT = 50L;

    /** Karma from lawful to far past the Red threshold, where the discount saturates. */
    private static final long[] KARMAS = {1000L, 250L, 0L, -1L, -25L, -50L, -99L, -100L, -500L, -1_000_000L};
    /** Heat from none to far past the Wanted threshold, where the markup saturates. */
    private static final long[] HEATS = {0L, 1L, 5L, 12L, 25L, 37L, 49L, 50L, 51L, 200L, 1_000_000L};
    /** Base prices either side of the rounding boundaries, plus one absurd but legal one. */
    private static final long[] BASES = {1L, 2L, 3L, 7L, 8L, 12L, 64L, 100L, 100_000L};

    private static PricingInputs inputs(long karma, long heat, boolean wanted) {
        return new PricingInputs(karma, heat, wanted, RED, WANTED_HEAT);
    }

    @Test
    void theFencePaysLessThanItChargesForEveryCombinationOfModifiers() {
        for (long base : BASES) {
            for (long karma : KARMAS) {
                for (long heat : HEATS) {
                    for (boolean wanted : new boolean[]{false, true}) {
                        PricingInputs in = inputs(karma, heat, wanted);
                        long sell = FencePricing.sellPrice(base, in, POLICY);
                        long buy = FencePricing.buyPrice(base, in, POLICY);
                        assertTrue(buy < sell, "base=" + base + " karma=" + karma + " heat=" + heat
                                + " wanted=" + wanted + ": paid " + buy + " and charged " + sell);
                        assertTrue(buy >= 1L, "a fence that pays nothing is not buying");
                    }
                }
            }
        }
    }

    @Test
    void whatTheFencePaysNeverRisesWithHeat() {
        for (long base : BASES) {
            for (long karma : KARMAS) {
                for (boolean wanted : new boolean[]{false, true}) {
                    long previous = Long.MAX_VALUE;
                    for (long heat : HEATS) {
                        long buy = FencePricing.buyPrice(base, inputs(karma, heat, wanted), POLICY);
                        assertTrue(buy <= previous, "base=" + base + " karma=" + karma + " wanted=" + wanted
                                + ": Heat " + heat + " raised the buy price from " + previous + " to " + buy);
                        previous = buy;
                    }
                }
            }
        }
    }

    @Test
    void beingHuntedNeverImprovesWhatAnItemSellsBackFor() {
        for (long base : BASES) {
            for (long heat : HEATS) {
                long calm = FencePricing.buyPrice(base, inputs(-50L, heat, false), POLICY);
                long hunted = FencePricing.buyPrice(base, inputs(-50L, heat, true), POLICY);
                assertTrue(hunted <= calm, "base=" + base + " heat=" + heat
                        + ": being Wanted raised the buy price from " + calm + " to " + hunted);
            }
        }
    }

    @Test
    void theBuyRatioIsClampedToTheLowestPriceTheFenceEverSellsAt() {
        // The single inequality the whole sweep rests on: a fence that paid a larger fraction of the
        // base price than the smallest fraction it ever charges could be bought from and sold back to.
        FencePolicy greedy = new FencePolicy(0.25D, 0.35D, 0.20D, 0.55D, 2.50D, 0.95D, 8L, 6, 8, 1);
        assertEquals(0.55D, greedy.buyPriceRatio(), 1.0E-9);
        // Clamping a clamped policy changes nothing, which is what makes a config reload safe.
        FencePolicy again = new FencePolicy(0.25D, 0.35D, 0.20D, 0.55D, 2.50D, greedy.buyPriceRatio(),
                8L, 6, 8, 1);
        assertEquals(greedy.buyPriceRatio(), again.buyPriceRatio(), 1.0E-9);

        for (long base : BASES) {
            for (long heat : HEATS) {
                PricingInputs in = inputs(-100L, heat, true);
                assertTrue(FencePricing.buyPrice(base, in, greedy) < FencePricing.sellPrice(base, in, greedy),
                        "base=" + base + " heat=" + heat + " under a greedy ratio");
            }
        }
    }
}
