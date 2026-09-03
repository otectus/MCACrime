package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ArrestPhase;
import dev.otectus.mcacrime.enforcement.ArrestReconcile;
import dev.otectus.mcacrime.enforcement.ArrestReconcile.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What happens to an arrest that a logout, a death, or a restart interrupted.
 *
 * <p>One case per rule, and the rules are ordered deliberately. The two easiest to get backwards are
 * asserted by name: a phase that says {@code JAILED} while no sentence is running is the stale half, and
 * an arrest whose destination has vanished must recover rather than complete — completing would jail
 * somebody nowhere.
 */
class ArrestReconcileTest {

    private static Outcome decide(ArrestPhase stored, boolean jailed, boolean custody,
                                  boolean anchor, boolean guard, boolean expired) {
        return ArrestReconcile.decide(stored, jailed, custody, anchor, guard, expired);
    }

    @Test
    void nothingStoredMeansNothingToDo() {
        assertEquals(Outcome.CLEAR, decide(ArrestPhase.NONE, false, false, false, false, false));
        assertEquals(Outcome.CLEAR, decide(null, false, false, false, false, false));
    }

    /** A running sentence outranks the phase. JailService owns a jailed player. */
    @Test
    void aRunningSentenceWinsOverAnyStoredPhase() {
        for (ArrestPhase phase : ArrestPhase.values()) {
            assertEquals(Outcome.CLEAR, decide(phase, true, true, true, true, false),
                    "stored " + phase + " with a sentence already running");
        }
    }

    @Test
    void jailedWithNoSentenceIsTheStaleHalfAndClears() {
        assertEquals(Outcome.CLEAR, decide(ArrestPhase.JAILED, false, false, true, true, false));
    }

    @Test
    void anUnansweredConfrontationSimplyLapses() {
        // The screen did not survive the disconnect and nothing was taken from the player, so there is
        // nothing to give back.
        assertEquals(Outcome.CLEAR, decide(ArrestPhase.CONFRONTED, false, false, true, true, false));
    }

    @Test
    void anInterruptedArrestWithSomewhereToGoIsFinishedRatherThanReplayed() {
        assertEquals(Outcome.COMPLETE_NOW, decide(ArrestPhase.SURRENDERED, false, true, true, false, false));
        assertEquals(Outcome.COMPLETE_NOW, decide(ArrestPhase.RESTRAINED, false, true, true, false, false));
        assertEquals(Outcome.COMPLETE_NOW, decide(ArrestPhase.ESCORTING, false, true, true, false, false),
                "no guard left to walk with");
        assertEquals(Outcome.COMPLETE_NOW, decide(ArrestPhase.ESCORTING, false, true, true, true, true),
                "the escort ran out of time while they were away");
    }

    @Test
    void aRelogInTheSameSessionPicksTheWalkBackUp() {
        assertEquals(Outcome.RESUME_ESCORT, decide(ArrestPhase.ESCORTING, false, true, true, true, false));
    }

    /**
     * The hard constraint of the whole subsystem: a destination that no longer exists must never leave
     * somebody restrained. Completing here would jail them nowhere.
     */
    @Test
    void anArrestWithNowhereToGoRecoversRatherThanCompleting() {
        assertEquals(Outcome.RECOVER, decide(ArrestPhase.SURRENDERED, false, true, false, false, false));
        assertEquals(Outcome.RECOVER, decide(ArrestPhase.RESTRAINED, false, true, false, false, false));
        assertEquals(Outcome.RECOVER, decide(ArrestPhase.ESCORTING, false, true, false, true, false));
    }

    @Test
    void aRecoveryWindowRunsOutOnTheOwnersOwnClock() {
        assertEquals(Outcome.RECOVER, decide(ArrestPhase.RECOVERY, false, false, true, false, false));
        assertEquals(Outcome.CLEAR, decide(ArrestPhase.RECOVERY, false, false, true, false, true));
    }

    /** No branch may leave a restraining phase standing without either finishing it or undoing it. */
    @Test
    void noRestrainingPhaseIsEverLeftUnresolved() {
        for (ArrestPhase phase : new ArrestPhase[] {ArrestPhase.SURRENDERED, ArrestPhase.RESTRAINED,
                ArrestPhase.ESCORTING}) {
            for (boolean jailed : new boolean[] {true, false}) {
                for (boolean custody : new boolean[] {true, false}) {
                    for (boolean anchor : new boolean[] {true, false}) {
                        for (boolean guard : new boolean[] {true, false}) {
                            for (boolean expired : new boolean[] {true, false}) {
                                Outcome outcome = decide(phase, jailed, custody, anchor, guard, expired);
                                boolean resolved = outcome == Outcome.CLEAR
                                        || outcome == Outcome.COMPLETE_NOW
                                        || outcome == Outcome.RECOVER
                                        || (outcome == Outcome.RESUME_ESCORT && anchor && guard);
                                assertEquals(true, resolved,
                                        phase + " left unresolved with anchor=" + anchor + " guard=" + guard);
                            }
                        }
                    }
                }
            }
        }
    }
}
