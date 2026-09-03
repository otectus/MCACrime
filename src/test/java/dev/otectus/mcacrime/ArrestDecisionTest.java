package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ChallengeBasis;
import dev.otectus.mcacrime.enforcement.EscortService;
import dev.otectus.mcacrime.enforcement.LegalTarget;
import dev.otectus.mcacrime.crime.Band;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three pure decisions the arrest flow turns on: whether a guard may stop somebody, whether force
 * is lawful once they have refused, and what an escort should do this tick.
 */
class ArrestDecisionTest {

    // ---------------------------------------------------------------- challenge basis

    @Test
    void aGuardWithNothingToSayDoesNotStopAnybody() {
        assertFalse(ChallengeBasis.hasBasis(0, false, false, false));
    }

    @Test
    void anyOneBasisIsEnough() {
        assertTrue(ChallengeBasis.hasBasis(1, false, false, false), "an open case is itself proof");
        assertTrue(ChallengeBasis.hasBasis(0, true, false, false), "a filed warrant");
        assertTrue(ChallengeBasis.hasBasis(0, false, true, false), "an escaped prisoner");
        assertTrue(ChallengeBasis.hasBasis(0, false, false, true), "an active kidnapper");
    }

    /**
     * The terms are an OR on purpose. A crime committed in front of a guard produces an actionable case
     * and no civilian report, so requiring both would make offences witnessed by the law itself
     * unenforceable until a bystander walked over.
     */
    @Test
    void anOpenCaseAloneSufficesWithoutAFiledReport() {
        assertTrue(ChallengeBasis.hasBasis(3, false, false, false));
    }

    // ---------------------------------------------------------------- legal target

    @Test
    void refusingAChallengeIsItsOwnBasisForForce() {
        assertTrue(LegalTarget.isLegalTarget(false, Band.GREY, false, false, false, true),
                "a refusal has to make force lawful, or the message shown on refusing is a lie");
    }

    @Test
    void aCompliantGreyPlayerIsNeverATarget() {
        assertFalse(LegalTarget.isLegalTarget(false, Band.GREY, false, false, false, false));
    }

    /** The older overloads keep their exact meaning: neither of them knows about resisting arrest. */
    @Test
    void theShorterOverloadsHaveNoResistTerm() {
        assertFalse(LegalTarget.isLegalTarget(false, Band.GREY, false, false, false));
        assertFalse(LegalTarget.isLegalTarget(false, Band.GREY, false, false));
    }

    // ---------------------------------------------------------------- escort

    @Test
    void anEscortKeepsWalkingWhileEverythingIsFine() {
        assertEquals(EscortService.Step.CONTINUE,
                EscortService.decide(true, 4.0, 256.0, 400.0, 9.0, 100L, 700L));
    }

    @Test
    void arrivalIsCheckedBeforeTheDeadline() {
        // Landing on the very last tick counts as having arrived, not as having run out of time.
        assertEquals(EscortService.Step.ARRIVED,
                EscortService.decide(true, 4.0, 256.0, 1.0, 9.0, 700L, 700L));
    }

    @Test
    void runningFromTheEscortIsResistingRatherThanATeleportBack() {
        assertEquals(EscortService.Step.TETHER_BROKEN,
                EscortService.decide(true, 900.0, 256.0, 400.0, 9.0, 100L, 700L));
    }

    /**
     * A guard that dies or unloads must not cancel a sentence the player already accepted, so a missing
     * escort completes the arrest rather than abandoning it.
     */
    @Test
    void aMissingGuardCompletesTheArrestInsteadOfCancellingIt() {
        assertEquals(EscortService.Step.COMPLETE_BY_TELEPORT,
                EscortService.decide(false, 0.0, 256.0, 400.0, 9.0, 100L, 700L));
    }

    @Test
    void anEscortThatRunsOutOfTimeStillPutsThemInTheCell() {
        assertEquals(EscortService.Step.COMPLETE_BY_TELEPORT,
                EscortService.decide(true, 4.0, 256.0, 400.0, 9.0, 900L, 700L));
    }

    /** A prisoner beyond the tether who is already at the cell has arrived; there is nothing left to flee. */
    @Test
    void arrivalBeatsTheTether() {
        assertEquals(EscortService.Step.ARRIVED,
                EscortService.decide(true, 900.0, 256.0, 1.0, 9.0, 100L, 700L));
    }
}
