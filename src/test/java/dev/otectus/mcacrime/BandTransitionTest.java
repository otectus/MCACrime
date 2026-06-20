package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies bands flip only when a threshold is crossed (the property the name-color sync relies on to
 * broadcast a packet only on a real transition, not per karma point).
 */
class BandTransitionTest {

    @Test
    void bandChangesOnlyAtThresholds() {
        long blue = 100;
        long red = -100;
        // walk karma up from -101 to 100, recording where the band changes
        Band prev = Band.fromKarma(-101, blue, red);
        int transitions = 0;
        for (long k = -100; k <= 100; k++) {
            Band now = Band.fromKarma(k, blue, red);
            if (now != prev) {
                transitions++;
                prev = now;
            }
        }
        // RED -> GREY at -99, then GREY -> BLUE at +100: exactly two transitions across the sweep.
        assertEquals(2, transitions);
    }

    @Test
    void isBandChangedAcrossExactBoundary() {
        assertTrue(Band.fromKarma(-100) != Band.fromKarma(-99));  // RED -> GREY
        assertTrue(Band.fromKarma(99) != Band.fromKarma(100));    // GREY -> BLUE
        assertFalse(Band.fromKarma(0) != Band.fromKarma(50));     // both GREY: no change
    }
}
