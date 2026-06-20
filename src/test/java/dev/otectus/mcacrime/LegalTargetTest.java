package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.LegalTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Legal-target truth table (spec §1.3): force is lawful only under the listed conditions. */
class LegalTargetTest {

    @Test
    void wantedIsAlwaysLegalTarget() {
        assertTrue(LegalTarget.isLegalTarget(true, Band.GREY, false, false));
        assertTrue(LegalTarget.isLegalTarget(true, Band.BLUE, false, false));
    }

    @Test
    void redIsLegalTargetOnlyWhenConfigured() {
        assertTrue(LegalTarget.isLegalTarget(false, Band.RED, true, false));
        assertFalse(LegalTarget.isLegalTarget(false, Band.RED, false, false));
    }

    @Test
    void escapedPrisonerIsAlwaysLegalTarget() {
        assertTrue(LegalTarget.isLegalTarget(false, Band.GREY, false, true));
    }

    @Test
    void lawfulPlayersAreNotTargets() {
        assertFalse(LegalTarget.isLegalTarget(false, Band.GREY, false, false));
        assertFalse(LegalTarget.isLegalTarget(false, Band.BLUE, true, false)); // Blue isn't Red
    }
}
