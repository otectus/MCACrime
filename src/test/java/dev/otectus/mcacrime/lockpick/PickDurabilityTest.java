package dev.otectus.mcacrime.lockpick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A used pick is not a broken pick, and the two are counted separately (M3.3, spec §8).
 *
 * <p>The specification asks for this in one sentence — "record lockpicks used/broken and successful
 * picks accurately. A used pick is not automatically a broken pick" — and it is the sort of rule that
 * is quietly wrong forever unless something asserts it. Upstream damages the stack and awards nothing.
 */
class PickDurabilityTest {

    /** The pick's registered durability: three sessions. */
    private static final int MAX = 3;

    @Test
    void usingAPickIsNotBreakingIt() {
        assertEquals(PickWear.Outcome.USED, PickWear.wear(0, MAX));
        assertEquals(PickWear.Outcome.USED, PickWear.wear(1, MAX));
        assertEquals(PickWear.Outcome.BROKEN, PickWear.wear(2, MAX),
                "the third session is the one that ends it");
    }

    @Test
    void damageNeverRunsPastTheMaximum() {
        assertEquals(1, PickWear.damageAfter(0, MAX));
        assertEquals(3, PickWear.damageAfter(2, MAX));
        assertEquals(3, PickWear.damageAfter(3, MAX), "a spent pick cannot be spent further");
        assertEquals(1, PickWear.damageAfter(-5, MAX),
                "a nonsensical damage value is treated as none, and the session still costs a point");
    }

    @Test
    void anIndestructiblePickIsAlwaysMerelyUsed() {
        assertEquals(PickWear.Outcome.USED, PickWear.wear(0, 0));
        assertEquals(PickWear.Outcome.USED, PickWear.wear(99, 0));
        assertEquals(7, PickWear.damageAfter(7, 0));
    }

    @Test
    void theTwoStatisticsAreDistinct() {
        assertNotEquals(PickWear.SUCCESSFUL_LOCKPICKS, PickWear.LOCKPICKS_BROKEN);
        assertEquals("mcacrime", PickWear.SUCCESSFUL_LOCKPICKS.getNamespace());
        assertEquals("successful_lockpicks", PickWear.SUCCESSFUL_LOCKPICKS.getPath());
        assertEquals("lockpicks_broken", PickWear.LOCKPICKS_BROKEN.getPath());
    }

    @Test
    void aSuccessfulPickAndABrokenOneAreIndependentFacts() {
        // Three sessions on one pick: two successes and a failure can break it, and a single success
        // need not. The two counters therefore cannot be derived from each other.
        assertTrue(PickWear.wear(0, MAX) == PickWear.Outcome.USED);
        assertTrue(PickWear.wear(MAX - 1, MAX) == PickWear.Outcome.BROKEN);
    }
}
