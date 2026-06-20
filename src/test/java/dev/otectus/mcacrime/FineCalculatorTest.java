package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.FineCalculator;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fine math (spec §6.1): base + per-Heat, Blue discount, jailable cutoff, Red barred. */
class FineCalculatorTest {

    @Test
    void noHeatOwesNothing() {
        assertEquals(OptionalLong.of(0L), FineCalculator.fineFor(0, Band.GREY, 8, 1, 80, 0.5, false));
    }

    @Test
    void greyFineIsBasePlusPerHeat() {
        assertEquals(OptionalLong.of(18L), FineCalculator.fineFor(10, Band.GREY, 8, 1, 80, 0.5, false));
    }

    @Test
    void blueGetsTheDiscount() {
        assertEquals(OptionalLong.of(9L), FineCalculator.fineFor(10, Band.BLUE, 8, 1, 80, 0.5, false)); // round(18 * 0.5)
    }

    @Test
    void atOrAboveJailableThresholdIsNotFinable() {
        assertTrue(FineCalculator.fineFor(80, Band.GREY, 8, 1, 80, 0.5, false).isEmpty());
        assertTrue(FineCalculator.fineFor(120, Band.GREY, 8, 1, 80, 0.5, false).isEmpty());
    }

    @Test
    void redIsBarredUnlessAllowed() {
        assertTrue(FineCalculator.fineFor(10, Band.RED, 8, 1, 80, 0.5, false).isEmpty());
        assertEquals(OptionalLong.of(18L), FineCalculator.fineFor(10, Band.RED, 8, 1, 80, 0.5, true));
    }
}
