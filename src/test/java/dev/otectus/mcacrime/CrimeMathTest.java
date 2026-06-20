package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.CrimeMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Karma/Heat arithmetic: clamping, the Wanted test, and online-tick decay accounting (spec §3, §7.1). */
class CrimeMathTest {

    @Test
    void clampSaturates() {
        assertEquals(100L, CrimeMath.clamp(150L, -100L, 100L));
        assertEquals(-100L, CrimeMath.clamp(-150L, -100L, 100L));
        assertEquals(50L, CrimeMath.clamp(50L, -100L, 100L));
        assertEquals(100L, CrimeMath.clamp(5L, 100L, -100L)); // mis-ordered range collapses to min
    }

    @Test
    void wantedThreshold() {
        assertTrue(CrimeMath.isWanted(50L, 50L));   // inclusive
        assertTrue(CrimeMath.isWanted(51L, 50L));
        assertFalse(CrimeMath.isWanted(49L, 50L));
    }

    @Test
    void stepsElapsedCountsWholePeriodsOnly() {
        assertEquals(0L, CrimeMath.stepsElapsed(23999L, 0L, 24000L));
        assertEquals(1L, CrimeMath.stepsElapsed(24000L, 0L, 24000L));
        assertEquals(2L, CrimeMath.stepsElapsed(48005L, 0L, 24000L));
        assertEquals(0L, CrimeMath.stepsElapsed(1000L, 0L, 0L));     // guard: non-positive period
        assertEquals(0L, CrimeMath.stepsElapsed(100L, 200L, 24000L)); // guard: negative elapsed
    }

    @Test
    void offlineTimeDoesNotDecay() {
        // A frozen online-tick counter (player logged out) yields zero elapsed steps -> no decay.
        long anchor = 5_000L;
        long frozenOnline = 5_000L; // unchanged across an offline span
        assertEquals(0L, CrimeMath.stepsElapsed(frozenOnline, anchor, 1200L));
    }

    @Test
    void decayMovesTowardZeroWithoutCrossing() {
        assertEquals(99L, CrimeMath.decayTowardZero(100L, 1L, 1L));
        assertEquals(0L, CrimeMath.decayTowardZero(100L, 1L, 200L));   // never overshoots past 0
        assertEquals(-40L, CrimeMath.decayTowardZero(-50L, 1L, 10L));
        assertEquals(0L, CrimeMath.decayTowardZero(-50L, 1L, 60L));
        assertEquals(0L, CrimeMath.decayTowardZero(0L, 1L, 5L));
        assertEquals(5L, CrimeMath.decayTowardZero(5L, 0L, 3L));       // zero rate: unchanged
    }
}
