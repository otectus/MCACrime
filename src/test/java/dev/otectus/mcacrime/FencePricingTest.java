package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.fence.FencePolicy;
import dev.otectus.mcacrime.economy.fence.FencePricing;
import dev.otectus.mcacrime.economy.fence.FencePricing.PricingInputs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fence pricing (0.5.1, spec §"Fence prices: karma versus Heat" and the "Fence pricing" test group).
 *
 * <p>The spec's whole point in that section is that Karma and Heat are not one axis. A lawful player
 * gets no criminal discount; a criminal gets one; a criminal the guards are watching pays a surcharge
 * that can outweigh it; a criminal the guards are actively hunting pays again on top. Those four rows
 * are the matrix below.
 *
 * <p>The second half is the invariant that closes the arbitrage loop: {@code buy < sell} for the same
 * item under <em>every</em> combination of modifiers. A single combination where they met would let a
 * player stand at one fence buying and re-selling the same lockpick forever.
 */
class FencePricingTest {

    private static final FencePolicy POLICY = FencePolicy.defaults();
    /** The shipped {@code bands.karmaRedThreshold} and {@code bands.wantedHeatThreshold}. */
    private static final long RED = -100L;
    private static final long WANTED_HEAT = 50L;

    private static PricingInputs inputs(long karma, long heat, boolean wanted) {
        return new PricingInputs(karma, heat, wanted, RED, WANTED_HEAT);
    }

    private static double multiplier(long karma, long heat, boolean wanted) {
        return FencePricing.sellMultiplier(inputs(karma, heat, wanted), POLICY);
    }

    // ------------------------------------------------------------------ the spec's matrix

    @Test
    void positiveKarmaAndNoHeatPaysListPrice() {
        assertEquals(1.0D, multiplier(250L, 0L, false), 1.0E-9,
                "a lawful player is not one of us and earns no criminal discount");
    }

    @Test
    void stronglyNegativeKarmaEarnsADiscount() {
        double criminal = multiplier(-100L, 0L, false);
        assertEquals(1.0D - POLICY.maxKarmaDiscount(), criminal, 1.0E-9);
        assertTrue(criminal < multiplier(250L, 0L, false));
    }

    @Test
    void heatChargesASurchargeThatCanOutweighTheDiscount() {
        double discounted = multiplier(-100L, 0L, false);
        double hot = multiplier(-100L, WANTED_HEAT, false);
        assertTrue(hot > discounted, "Heat must raise the price a criminal discount lowered");
        assertTrue(hot > 1.0D,
                "the spec requires the surcharge to be able to outweigh the discount entirely, was " + hot);
    }

    @Test
    void beingWantedChargesAgainOnTop() {
        double hot = multiplier(-100L, WANTED_HEAT, false);
        double hunted = multiplier(-100L, WANTED_HEAT, true);
        assertEquals(hot + POLICY.wantedMarkup(), hunted, 1.0E-9);
    }

    @Test
    void karmaAndHeatAreNotCollapsedIntoOneAxis() {
        // Same Heat, different Karma: the discount still applies underneath the surcharge.
        assertTrue(multiplier(-100L, WANTED_HEAT, false) < multiplier(0L, WANTED_HEAT, false));
    }

    @Test
    void boundaryValuesClampRatherThanRunAway() {
        // A pack that turned both modifiers up past the clamps: neither runs away with the price.
        FencePolicy extreme = new FencePolicy(0.9D, 5.0D, 1.0D, 0.55D, 2.50D, 0.5D, 8L, 6, 1);
        assertEquals(0.55D, FencePricing.sellMultiplier(inputs(-1_000_000L, 0L, false), extreme), 1.0E-9);
        assertEquals(2.50D, FencePricing.sellMultiplier(inputs(-10L, 1_000_000L, true), extreme), 1.0E-9);
    }

    @Test
    void normalisationSaturatesAtTheThresholdsRatherThanBeyondThem() {
        assertEquals(1.0D, FencePricing.criminalAffinity(inputs(-100_000L, 0L, false)), 1.0E-9);
        assertEquals(0.0D, FencePricing.criminalAffinity(inputs(500L, 0L, false)), 1.0E-9);
        assertEquals(1.0D, FencePricing.heatRisk(inputs(0L, 100_000L, false)), 1.0E-9);
        assertEquals(0.0D, FencePricing.heatRisk(inputs(0L, 0L, false)), 1.0E-9);
    }

    // ------------------------------------------------------------------ buy < sell, everywhere

    @Test
    void theFenceAlwaysPaysLessThanItCharges() {
        long[] karmas = {1000L, 100L, 0L, -1L, -50L, -100L, -1000L, -1_000_000L};
        long[] heats = {0L, 1L, 25L, 50L, 500L, 1_000_000L};
        long[] bases = {1L, 8L, 1000L};
        for (long base : bases) {
            for (long karma : karmas) {
                for (long heat : heats) {
                    for (boolean wanted : new boolean[]{false, true}) {
                        PricingInputs inputs = inputs(karma, heat, wanted);
                        long sell = FencePricing.sellPrice(base, inputs, POLICY);
                        long buy = FencePricing.buyPrice(base, inputs, POLICY);
                        assertTrue(buy >= 1L,
                                "a fence that pays nothing is not buying: base=" + base + " karma=" + karma
                                        + " heat=" + heat + " wanted=" + wanted);
                        assertTrue(buy < sell,
                                "buy (" + buy + ") must be below sell (" + sell + ") for base=" + base
                                        + " karma=" + karma + " heat=" + heat + " wanted=" + wanted);
                    }
                }
            }
        }
    }

    @Test
    void theCheapestPossibleGoodStillHasRoomForABuyPrice() {
        // base 1 at the minimum multiplier is the tightest case in the grid above, and the reason the
        // sale price has a floor of two rather than one.
        PricingInputs cheapest = inputs(-1_000_000L, 0L, false);
        assertEquals(2L, FencePricing.sellPrice(1L, cheapest, POLICY));
        assertEquals(1L, FencePricing.buyPrice(1L, cheapest, POLICY));
    }
}
