package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Band derivation boundaries (spec §1.1) and the defensive fallback for a mis-ordered config. */
class BandLogicTest {

    @Test
    void defaultThresholdBoundaries() {
        assertEquals(Band.RED, Band.fromKarma(-101));
        assertEquals(Band.RED, Band.fromKarma(-100));   // inclusive
        assertEquals(Band.GREY, Band.fromKarma(-99));
        assertEquals(Band.GREY, Band.fromKarma(0));
        assertEquals(Band.GREY, Band.fromKarma(99));
        assertEquals(Band.BLUE, Band.fromKarma(100));   // inclusive
        assertEquals(Band.BLUE, Band.fromKarma(101));
    }

    @Test
    void customThresholds() {
        assertEquals(Band.BLUE, Band.fromKarma(50, 50, -50));
        assertEquals(Band.GREY, Band.fromKarma(49, 50, -50));
        assertEquals(Band.RED, Band.fromKarma(-50, 50, -50));
    }

    @Test
    void misorderedThresholdsFallBackToDefaults() {
        // blue (-100) <= red (100): nonsense — must use +100/-100 defaults instead of inverting bands.
        assertEquals(Band.GREY, Band.fromKarma(0, -100, 100));
        assertEquals(Band.BLUE, Band.fromKarma(200, -100, 100));
        assertEquals(Band.RED, Band.fromKarma(-200, -100, 100));
    }
}
