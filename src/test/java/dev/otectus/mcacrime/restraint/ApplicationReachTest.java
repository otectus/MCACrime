package dev.otectus.mcacrime.restraint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code restraints.application.maxRangeBlocks} and {@code requireLineOfSight} (§3.12).
 *
 * <p>Both keys shipped read by nothing but the validator and the preset writer, so an application
 * was never refused for distance or sight at all -- and the crime menu, which checks reach only when
 * it opens, accepted a Restrain click from anywhere for as long as it stayed open. The rule is pinned
 * here as the pure function {@code RestraintService.evaluate} now consults.
 */
class ApplicationReachTest {

    @Test
    void withinRangeAndInSightIsAllowed() {
        assertTrue(RestraintService.reachAllows(9.0D, true, 4.0D, true));
        assertTrue(RestraintService.reachAllows(16.0D, true, 4.0D, true), "the boundary is inclusive");
    }

    @Test
    void beyondTheConfiguredRangeIsRefused() {
        assertFalse(RestraintService.reachAllows(16.01D, true, 4.0D, true));
        assertFalse(RestraintService.reachAllows(900.0D, true, 4.0D, false),
                "switching sight off does not switch distance off");
    }

    @Test
    void sightIsRequiredOnlyWhenConfigured() {
        assertFalse(RestraintService.reachAllows(4.0D, false, 4.0D, true));
        assertTrue(RestraintService.reachAllows(4.0D, false, 4.0D, false));
    }

    @Test
    void nonsenseInputsNeverPass() {
        assertFalse(RestraintService.reachAllows(Double.NaN, true, 4.0D, true));
        assertFalse(RestraintService.reachAllows(-1.0D, true, 4.0D, true));
        assertFalse(RestraintService.reachAllows(0.0D, true, 0.0D, true));
    }
}
