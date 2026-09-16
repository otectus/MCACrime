package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.NpcMuggerEligibility;
import dev.otectus.mcacrime.job.NpcMuggerEligibility.Context;
import dev.otectus.mcacrime.job.NpcMuggerEligibility.Facts;
import dev.otectus.mcacrime.job.NpcMuggerEligibilityReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The role invariant read from the guard population pass's side (0.7.2, spec §3).
 *
 * <p>Excluding law from crime is only half of it. The guard-population pass converts ordinary
 * villagers on a cooldown, and if it may pick the village thief then the conflicting state this stage
 * exists to prevent simply arrives from the other direction — a guard with a live Thief record, made
 * by the mod itself, one tick after the sweep refused to make one.
 */
class GuardPromotionEligibilityTest {

    @Test
    void aCriminalIsNeverAPromotionCandidate() {
        assertTrue(NpcMuggerEligibility.guardPromotionBlocked(true));
    }

    @Test
    void anOrdinaryVillagerStillIs() {
        assertFalse(NpcMuggerEligibility.guardPromotionBlocked(false));
    }

    /**
     * The candidate list is built once per pass and promotions happen in a loop afterwards, so the
     * question is asked twice. A job assigned in between — by the sweep, a command, or a listener —
     * must be seen by the second reading, which is why the service re-reads rather than trusting the
     * list it built.
     */
    @Test
    void aJobAcquiredAfterSelectionStillBlocksThePromotion() {
        boolean criminalAtSelection = false;
        boolean criminalAtPromotion = true;
        assertFalse(NpcMuggerEligibility.guardPromotionBlocked(criminalAtSelection));
        assertTrue(NpcMuggerEligibility.guardPromotionBlocked(criminalAtPromotion),
                "the immediate pre-promotion check is what catches this");
    }

    /** And the reverse: a villager promoted to guard can no longer be recruited as a criminal. */
    @Test
    void aFreshlyPromotedGuardIsNoLongerRecruitable() {
        Facts promoted = new Facts(true, true, true, true, true, false, false);
        assertEquals(NpcMuggerEligibilityReason.RESPONDER,
                NpcMuggerEligibility.evaluate(promoted, Context.ASSIGNMENT).reason());
    }
}
