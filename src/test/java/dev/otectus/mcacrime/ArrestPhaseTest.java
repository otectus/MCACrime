package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ArrestPhase;
import dev.otectus.mcacrime.enforcement.ArrestPhases;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arrest lifecycle: the gate table and the legal-edge table.
 *
 * <p>These assertions are the whole point of collapsing five scattered stores into one phase. Each one
 * describes a combination that used to be representable and wrong — a challenge opening against
 * somebody already in a cell, a guard swinging at a prisoner they are escorting, an arrest jumping
 * straight to a sentence it never assessed.
 */
class ArrestPhaseTest {

    // ---------------------------------------------------------------- the gate table

    @Test
    void onlyAnUnarrestedPlayerMayBeChallenged() {
        assertTrue(ArrestPhases.canOpenChallenge(ArrestPhase.NONE));
        for (ArrestPhase phase : ArrestPhase.values()) {
            if (phase != ArrestPhase.NONE) {
                assertFalse(ArrestPhases.canOpenChallenge(phase),
                        phase + " must not admit a second confrontation screen");
            }
        }
    }

    /**
     * The exact case the old code could not see. Heat and charges both survive an arrest, so a player
     * being walked to a cell stays a Legal Target for the whole walk and was handed a fresh screen
     * every scan — including after they had surrendered.
     */
    @Test
    void aPlayerBeingEscortedIsNotChallengedAgain() {
        assertFalse(ArrestPhases.canOpenChallenge(ArrestPhase.ESCORTING));
        assertFalse(ArrestPhases.canOpenChallenge(ArrestPhase.SURRENDERED));
    }

    @Test
    void restraintCoversTheWholeWalkAndNothingElse() {
        assertFalse(ArrestPhases.isRestrained(ArrestPhase.NONE));
        assertFalse(ArrestPhases.isRestrained(ArrestPhase.CONFRONTED), "being asked is not being cuffed");
        assertTrue(ArrestPhases.isRestrained(ArrestPhase.SURRENDERED));
        assertTrue(ArrestPhases.isRestrained(ArrestPhase.RESTRAINED));
        assertTrue(ArrestPhases.isRestrained(ArrestPhase.ESCORTING));
        assertFalse(ArrestPhases.isRestrained(ArrestPhase.JAILED), "a cell holds them, not the cuffs");
        assertFalse(ArrestPhases.isRestrained(ArrestPhase.RECOVERY));
    }

    @Test
    void escortAndSentenceAreMutuallyExclusive() {
        for (ArrestPhase phase : ArrestPhase.values()) {
            assertFalse(ArrestPhases.escortRuns(phase) && ArrestPhases.sentenceRunning(phase),
                    phase + " cannot be both walking and serving");
        }
        assertTrue(ArrestPhases.escortRuns(ArrestPhase.ESCORTING));
        assertTrue(ArrestPhases.sentenceRunning(ArrestPhase.JAILED));
    }

    @Test
    void inProgressCoversEverythingFromTheAnswerToTheEndOfTheSentence() {
        assertFalse(ArrestPhases.inProgress(ArrestPhase.NONE));
        assertFalse(ArrestPhases.inProgress(ArrestPhase.CONFRONTED), "an unanswered question is not an arrest");
        assertTrue(ArrestPhases.inProgress(ArrestPhase.SURRENDERED));
        assertTrue(ArrestPhases.inProgress(ArrestPhase.RESTRAINED));
        assertTrue(ArrestPhases.inProgress(ArrestPhase.ESCORTING));
        assertTrue(ArrestPhases.inProgress(ArrestPhase.JAILED));
        assertFalse(ArrestPhases.inProgress(ArrestPhase.RECOVERY));
    }

    @Test
    void forceIsNeverPermittedAgainstSomebodyAlreadyInCustody() {
        for (ArrestPhase phase : new ArrestPhase[] {ArrestPhase.CONFRONTED, ArrestPhase.SURRENDERED,
                ArrestPhase.RESTRAINED, ArrestPhase.ESCORTING, ArrestPhase.JAILED}) {
            assertFalse(ArrestPhases.forcePermitted(phase, true, true),
                    "swinging at a prisoner in phase " + phase);
            assertFalse(ArrestPhases.forcePermitted(phase, false, true),
                    "turning challenges off is not a licence to attack a prisoner");
        }
    }

    @Test
    void forceFollowsTheResistingFlagWhenNoArrestIsRunning() {
        assertTrue(ArrestPhases.forcePermitted(ArrestPhase.NONE, true, true));
        assertFalse(ArrestPhases.forcePermitted(ArrestPhase.NONE, true, false));
        assertTrue(ArrestPhases.forcePermitted(ArrestPhase.NONE, false, false),
                "with challenges off, behaviour reverts to attacking a Legal Target on sight");
    }

    /**
     * Recovery suppresses the screen, not the law. Otherwise surrendering to a guard with nowhere to
     * put you would buy temporary immunity from force you were already subject to.
     */
    @Test
    void recoveryDoesNotShieldAResistingPlayerFromForce() {
        assertTrue(ArrestPhases.forcePermitted(ArrestPhase.RECOVERY, true, true));
        assertFalse(ArrestPhases.forcePermitted(ArrestPhase.RECOVERY, true, false));
        assertFalse(ArrestPhases.canOpenChallenge(ArrestPhase.RECOVERY));
    }

    // ---------------------------------------------------------------- the edge table

    @Test
    void theHappyPathIsWalkableEndToEnd() {
        assertTrue(ArrestPhases.allows(ArrestPhase.NONE, ArrestPhase.CONFRONTED));
        assertTrue(ArrestPhases.allows(ArrestPhase.CONFRONTED, ArrestPhase.SURRENDERED));
        assertTrue(ArrestPhases.allows(ArrestPhase.SURRENDERED, ArrestPhase.RESTRAINED));
        assertTrue(ArrestPhases.allows(ArrestPhase.RESTRAINED, ArrestPhase.ESCORTING));
        assertTrue(ArrestPhases.allows(ArrestPhase.ESCORTING, ArrestPhase.JAILED));
    }

    @Test
    void standingDownAndFailingAreAlwaysAvailable() {
        for (ArrestPhase from : ArrestPhase.values()) {
            assertTrue(ArrestPhases.allows(from, ArrestPhase.NONE), from + " -> NONE");
            assertTrue(ArrestPhases.allows(from, ArrestPhase.RECOVERY), from + " -> RECOVERY");
        }
    }

    @Test
    void anArrestCannotSkipTheStepsThatAssessIt() {
        assertFalse(ArrestPhases.allows(ArrestPhase.NONE, ArrestPhase.ESCORTING),
                "escorting somebody nobody ever stopped");
        assertFalse(ArrestPhases.allows(ArrestPhase.NONE, ArrestPhase.RESTRAINED));
        assertFalse(ArrestPhases.allows(ArrestPhase.CONFRONTED, ArrestPhase.ESCORTING));
    }

    @Test
    void aSentenceAndAFailedArrestAreBothTerminal() {
        assertFalse(ArrestPhases.allows(ArrestPhase.JAILED, ArrestPhase.ESCORTING),
                "a running sentence must not be walked backwards into an escort");
        assertFalse(ArrestPhases.allows(ArrestPhase.JAILED, ArrestPhase.SURRENDERED));
        assertFalse(ArrestPhases.allows(ArrestPhase.RECOVERY, ArrestPhase.SURRENDERED),
                "a failed arrest restarts from NONE, never from the middle");
        assertFalse(ArrestPhases.allows(ArrestPhase.RECOVERY, ArrestPhase.ESCORTING));
    }

    /** A replayed packet must settle nothing twice, so re-entering the same phase is a no-op, not a refusal. */
    @Test
    void reEnteringTheSamePhaseIsAllowed() {
        for (ArrestPhase phase : ArrestPhase.values()) {
            assertTrue(ArrestPhases.allows(phase, phase), phase.name());
        }
    }

    @Test
    void nullsAreRefusedRatherThanThrown() {
        assertFalse(ArrestPhases.allows(null, ArrestPhase.JAILED));
        assertFalse(ArrestPhases.allows(ArrestPhase.NONE, null));
    }

    // ---------------------------------------------------------------- parsing

    @Test
    void anUnknownPhaseNameReadsAsNoArrest() {
        assertEquals(ArrestPhase.NONE, ArrestPhase.parse(null));
        assertEquals(ArrestPhase.NONE, ArrestPhase.parse(""));
        assertEquals(ArrestPhase.NONE, ArrestPhase.parse("PROCESSING"), "a hand-edited or future save");
        assertEquals(ArrestPhase.ESCORTING, ArrestPhase.parse("ESCORTING"));
    }
}
