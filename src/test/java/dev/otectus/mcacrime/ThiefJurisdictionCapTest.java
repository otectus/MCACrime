package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.CriminalJobAssigner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live-thief cap per jurisdiction (0.7.0, plan §4.5).
 *
 * <p>{@code criminalAssignmentCooldownDays} throttles how <em>often</em> a village produces a criminal,
 * which is a different promise: a village played in for a month accumulates thieves one at a time with
 * every cooldown satisfied. The count is the missing half, and the boundary — at the cap, not above it
 * — is the part a reading of the sweep cannot settle.
 */
class ThiefJurisdictionCapTest {

    @Test
    void aJurisdictionAtItsCapTakesNoMoreThieves() {
        assertTrue(CriminalJobAssigner.jurisdictionAllows(0, 2));
        assertTrue(CriminalJobAssigner.jurisdictionAllows(1, 2));
        assertFalse(CriminalJobAssigner.jurisdictionAllows(2, 2), "at the cap is full, not one short");
        assertFalse(CriminalJobAssigner.jurisdictionAllows(3, 2));
    }

    @Test
    void aCapOfZeroAssignsNobodyRatherThanEverybody() {
        assertFalse(CriminalJobAssigner.jurisdictionAllows(0, 0));
    }

    @Test
    void theShippedDefaultsAreTheRetunedOnes() {
        assertEquals(2, McaCrimeConfig.COMMON.maxActiveThievesPerJurisdiction.getDefault());
        assertEquals(0.01D, McaCrimeConfig.COMMON.villageThiefChance.getDefault(), 1.0E-9D);
        assertEquals(2400, McaCrimeConfig.COMMON.assignmentScanIntervalTicks.getDefault());
        assertEquals(24000, McaCrimeConfig.COMMON.thiefMugCooldownTicks.getDefault());
    }
}
