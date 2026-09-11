package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ContrabandPolicy;
import dev.otectus.mcacrime.enforcement.ContrabandSearchRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a guard may search a player (0.7.0, plan §5.2).
 *
 * <p>Six gates, and each one is asserted alone against an otherwise-passing row, because a search is
 * not free to the player being searched: a gate that quietly stopped applying would read in play as
 * guards frisking everybody, which is exactly the complaint contraband must not become.
 */
class ContrabandDiscoveryGateTest {

    /** Everything true, forty ticks of watching, a roll well inside a 15% chance. */
    private static boolean gate(boolean enabled, boolean patrol, boolean inRange, long los,
                                boolean requiresSuspicion, boolean suspicious, double chance, double roll) {
        return ContrabandSearchRules.searches(enabled, patrol, inRange, los, 40, requiresSuspicion,
                suspicious, chance, roll);
    }

    @Test
    void everythingInPlaceSearches() {
        assertTrue(gate(true, true, true, 40L, true, true, 0.15D, 0.01D));
    }

    @Test
    void eachGateAloneRefusesAnOtherwisePassingSearch() {
        assertFalse(gate(false, true, true, 40L, true, true, 0.15D, 0.01D), "contraband off");
        assertFalse(gate(true, false, true, 40L, true, true, 0.15D, 0.01D), "mode is ARREST_ONLY");
        assertFalse(gate(true, true, false, 40L, true, true, 0.15D, 0.01D), "no guard within radius");
        assertFalse(gate(true, true, true, 39L, true, true, 0.15D, 0.01D), "one tick short of the look");
        assertFalse(gate(true, true, true, 40L, true, false, 0.15D, 0.01D), "no reason to stop them");
        assertFalse(gate(true, true, true, 40L, true, true, 0.15D, 0.99D), "the roll refused");
    }

    @Test
    void suspicionIsOptionalAndZeroRequiredLineOfSightMeansAGlanceIsEnough() {
        assertTrue(gate(true, true, true, 40L, false, false, 1.0D, 0.99D));
        assertTrue(ContrabandSearchRules.searches(true, true, true, 0L, 0, false, false, 1.0D, 0.99D));
    }

    @Test
    void aChanceOfZeroNeverFiresAndOneAlwaysDoes() {
        assertFalse(ContrabandSearchRules.rolled(0.0D, 0.0D));
        assertTrue(ContrabandSearchRules.rolled(1.0D, 0.999999D));
        assertTrue(ContrabandSearchRules.rolled(0.5D, 0.499D));
        assertFalse(ContrabandSearchRules.rolled(0.5D, 0.5D), "the roll is exclusive at the boundary");
    }

    @Test
    void theLineOfSightCounterAccumulatesWhileSeenAndResetsTheMomentItIsNot() {
        assertEquals(40L, ContrabandSearchRules.accumulate(0L, true, 40));
        assertEquals(80L, ContrabandSearchRules.accumulate(40L, true, 40));
        assertEquals(0L, ContrabandSearchRules.accumulate(80L, false, 40),
                "forty ticks of watching is not forty ticks with a wall in the way");
    }

    @Test
    void theDiscoveryModesSayWhichSearchTheyAllowAndAnUnknownOneDegradesToBoth() {
        assertTrue(ContrabandPolicy.DiscoveryMode.BOTH.allowsPatrol());
        assertTrue(ContrabandPolicy.DiscoveryMode.BOTH.allowsArrest());
        assertTrue(ContrabandPolicy.DiscoveryMode.GUARD_PATROL.allowsPatrol());
        assertFalse(ContrabandPolicy.DiscoveryMode.GUARD_PATROL.allowsArrest());
        assertFalse(ContrabandPolicy.DiscoveryMode.ARREST_ONLY.allowsPatrol());
        assertTrue(ContrabandPolicy.DiscoveryMode.ARREST_ONLY.allowsArrest());
        assertEquals(ContrabandPolicy.DiscoveryMode.ARREST_ONLY, ContrabandPolicy.parseMode("arrest_only"));
        assertEquals(ContrabandPolicy.DiscoveryMode.BOTH, ContrabandPolicy.parseMode("nonsense"));
        assertEquals(ContrabandPolicy.DiscoveryMode.BOTH, ContrabandPolicy.parseMode(null));
    }
}
