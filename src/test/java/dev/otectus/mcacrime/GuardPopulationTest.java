package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.GuardPopulation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard-population arithmetic.
 *
 * <p>The three worked examples from the requirement are asserted verbatim, because they are what an
 * operator will check the feature against. The rest pins the two rules that are easy to get subtly
 * wrong: an empty village must not be given a guard by the minimum, and a half-unloaded village must
 * not read as half-guardless.
 */
class GuardPopulationTest {

    private static final double TEN_PERCENT = 0.10;

    // ---------------------------------------------------------------- the target

    @Test
    void theWorkedExamplesFromTheRequirement() {
        assertEquals(1, GuardPopulation.targetGuards(10, TEN_PERCENT, 1));
        assertEquals(2, GuardPopulation.targetGuards(20, TEN_PERCENT, 1));
        assertEquals(5, GuardPopulation.targetGuards(50, TEN_PERCENT, 1));
    }

    @Test
    void theTargetRoundsUp() {
        assertEquals(2, GuardPopulation.targetGuards(11, TEN_PERCENT, 1));
        assertEquals(3, GuardPopulation.targetGuards(21, TEN_PERCENT, 1));
        assertEquals(1, GuardPopulation.targetGuards(1, TEN_PERCENT, 1));
    }

    /** The minimum is a floor on a real village, not a way to conjure a guard out of nobody. */
    @Test
    void anEmptyVillageIsLeftAlone() {
        assertEquals(0, GuardPopulation.targetGuards(0, TEN_PERCENT, 1));
        assertEquals(0, GuardPopulation.targetGuards(-3, TEN_PERCENT, 5));
    }

    @Test
    void theMinimumFloorsSmallVillagesAndTheRatioCeilingsLargeOnes() {
        assertEquals(3, GuardPopulation.targetGuards(5, TEN_PERCENT, 3), "the floor wins here");
        assertEquals(10, GuardPopulation.targetGuards(100, TEN_PERCENT, 3), "the ratio wins here");
        assertEquals(1, GuardPopulation.targetGuards(100, 0.0, 1), "a ratio of zero still respects the floor");
        assertEquals(100, GuardPopulation.targetGuards(100, 1.0, 1));
    }

    @Test
    void theTargetNeverExceedsThePopulation() {
        assertEquals(4, GuardPopulation.targetGuards(4, TEN_PERCENT, 50),
                "a minimum larger than the village cannot ask for more guards than there are villagers");
        assertEquals(3, GuardPopulation.targetGuards(3, 2.0, 1), "an out-of-range ratio is clamped");
    }

    // ---------------------------------------------------------------- the unloaded credit

    /**
     * MCA credits the unloaded remainder for exactly this reason. Without it, a village whose far half
     * is out of render distance reads as short by half its guards, and the pass converts every loaded
     * adult it can find.
     */
    @Test
    void unloadedResidentsAreCreditedTheSameShare() {
        assertEquals(3, GuardPopulation.assumedUnloadedGuards(40, 10, TEN_PERCENT));
        assertEquals(0, GuardPopulation.assumedUnloadedGuards(10, 10, TEN_PERCENT),
                "a fully loaded village credits nothing");
    }

    @Test
    void aStalePopulationCountNeverProducesANegativeCredit() {
        assertEquals(0, GuardPopulation.assumedUnloadedGuards(10, 40, TEN_PERCENT));
        assertEquals(0, GuardPopulation.assumedUnloadedGuards(-5, 0, TEN_PERCENT));
    }

    // ---------------------------------------------------------------- conversions

    @Test
    void aVillageAtOrOverTargetConvertsNobody() {
        assertEquals(0, GuardPopulation.conversionsNeeded(20, 20, 2, TEN_PERCENT, 1, 1));
        assertEquals(0, GuardPopulation.conversionsNeeded(20, 20, 9, TEN_PERCENT, 1, 1),
                "well over target is still zero, never a negative");
    }

    @Test
    void aShortfallIsCappedByTheePerPassLimit() {
        assertEquals(1, GuardPopulation.conversionsNeeded(100, 100, 0, TEN_PERCENT, 1, 1));
        assertEquals(4, GuardPopulation.conversionsNeeded(100, 100, 0, TEN_PERCENT, 1, 4));
        assertEquals(10, GuardPopulation.conversionsNeeded(100, 100, 0, TEN_PERCENT, 1, 99),
                "the cap never asks for more than the shortfall");
    }

    @Test
    void existingGuardsAreCountedRatherThanIgnored() {
        assertEquals(0, GuardPopulation.conversionsNeeded(50, 50, 5, TEN_PERCENT, 1, 8));
        assertEquals(3, GuardPopulation.conversionsNeeded(50, 50, 2, TEN_PERCENT, 1, 8));
    }

    // ---------------------------------------------------------------- the property that matters

    /**
     * The requirement asks that a population fluctuating by one cannot cause guards to be created and
     * removed repeatedly. Since the pass only ever adds, the real property to assert is that the guard
     * count is monotonically non-decreasing across a wobbling population, and that it converges.
     */
    @Test
    void aWobblingPopulationConvergesAndNeverLosesGuards() {
        int[] populations = {20, 21, 20, 21, 20, 21, 20, 21, 20, 21, 20, 21};
        int guards = 0;
        int previous = 0;
        for (int population : populations) {
            int convert = GuardPopulation.conversionsNeeded(population, population, guards,
                    TEN_PERCENT, 1, 1);
            guards += convert;
            assertTrue(guards >= previous, "the guard count went down");
            previous = guards;
        }
        assertEquals(3, guards, "converges on ceil(21 * 0.10) and then stops");

        // And having converged, further wobbling costs nothing at all.
        for (int population : populations) {
            assertEquals(0, GuardPopulation.conversionsNeeded(population, population, guards,
                    TEN_PERCENT, 1, 1));
        }
    }
}
