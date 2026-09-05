package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.thief.ThiefState;
import dev.otectus.mcacrime.ai.thief.ThiefStateMachine;
import dev.otectus.mcacrime.ai.thief.ThiefStateMachine.ThiefSignals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Every arrow in the diagram of spec §"Thief behavioral state machine".
 *
 * <p>Two of these are the release contract rather than mere coverage: a mugging in progress ends the
 * moment the victim arms themselves, and a guard reaching the thief outranks the timer. Both are the
 * counterplay the spec asks for, and both are one wrong branch away from a thief that robs people
 * through a drawn sword.
 */
class ThiefStateMachineTest {

    /** All ten signals false: nothing is happening. */
    private static ThiefSignals none() {
        return new ThiefSignals(false, false, false, false, false, false, false, false, false, false);
    }

    private static ThiefSignals victim(boolean inReach) {
        return new ThiefSignals(true, inReach, false, false, false, false, false, false, false, false);
    }

    @Test
    void anIdleThiefStartsScouting() {
        assertEquals(ThiefState.SCOUTING, ThiefStateMachine.next(ThiefState.IDLE, none()));
    }

    @Test
    void scoutingApproachesOnlyWhenAVictimIsFoundAndTheStreetIsClear() {
        assertEquals(ThiefState.SCOUTING, ThiefStateMachine.next(ThiefState.SCOUTING, none()));
        assertEquals(ThiefState.APPROACHING, ThiefStateMachine.next(ThiefState.SCOUTING, victim(false)));
        ThiefSignals watched = new ThiefSignals(true, false, true, false, false, false, false, false, false, false);
        assertEquals(ThiefState.SCOUTING, ThiefStateMachine.next(ThiefState.SCOUTING, watched));
    }

    @Test
    void threateningIsNeverReachedWithoutBeingInReach() {
        for (ThiefState state : ThiefState.values()) {
            assertNotEquals(ThiefState.THREATENING, ThiefStateMachine.next(state, victim(false)),
                    "reached THREATENING from " + state + " without being in reach");
        }
        assertEquals(ThiefState.THREATENING, ThiefStateMachine.next(ThiefState.APPROACHING, victim(true)));
    }

    @Test
    void anApproachThatLosesItsVictimGoesBackToLookingRatherThanRunning() {
        ThiefSignals gone = new ThiefSignals(false, false, false, false, true, false, false, false, false, false);
        assertEquals(ThiefState.SCOUTING, ThiefStateMachine.next(ThiefState.APPROACHING, gone));
        ThiefSignals armed = new ThiefSignals(true, false, false, true, false, false, false, false, false, false);
        assertEquals(ThiefState.SCOUTING, ThiefStateMachine.next(ThiefState.APPROACHING, armed));
    }

    @Test
    void anApproachBrokenOffByGuardRiskRuns() {
        ThiefSignals hot = new ThiefSignals(true, false, true, false, false, false, false, false, false, false);
        assertEquals(ThiefState.FLEEING, ThiefStateMachine.next(ThiefState.APPROACHING, hot));
    }

    @Test
    void aVictimWhoWalksOffDuringTheThreatIsFollowedAgain() {
        assertEquals(ThiefState.APPROACHING, ThiefStateMachine.next(ThiefState.THREATENING, victim(false)));
    }

    @Test
    void theThreatBecomesAMuggingOnceTheSessionOpens() {
        ThiefSignals opened = new ThiefSignals(true, true, false, false, false, true, false, false, false, false);
        assertEquals(ThiefState.MUGGING, ThiefStateMachine.next(ThiefState.THREATENING, opened));
    }

    @Test
    void drawingAWeaponMidMuggingEndsIt() {
        ThiefSignals armed = new ThiefSignals(true, true, false, true, false, false, false, false, false, false);
        assertEquals(ThiefState.FLEEING, ThiefStateMachine.next(ThiefState.MUGGING, armed));
    }

    @Test
    void aGuardReachingTheThiefOutranksTheTimer() {
        ThiefSignals intervened = new ThiefSignals(true, true, false, false, false, true, true, false, false, false);
        assertEquals(ThiefState.ARRESTED, ThiefStateMachine.next(ThiefState.MUGGING, intervened));
        assertEquals(ThiefState.ARRESTED, ThiefStateMachine.next(ThiefState.THREATENING, intervened));
        assertEquals(ThiefState.ARRESTED, ThiefStateMachine.next(ThiefState.APPROACHING, intervened));
    }

    @Test
    void aCompletedMuggingRunsAndThenCoolsDown() {
        ThiefSignals done = new ThiefSignals(true, true, false, false, false, true, false, false, false, false);
        assertEquals(ThiefState.FLEEING, ThiefStateMachine.next(ThiefState.MUGGING, done));
        ThiefSignals escaped = new ThiefSignals(false, false, false, false, false, false, false, false, false, true);
        assertEquals(ThiefState.COOLDOWN, ThiefStateMachine.next(ThiefState.FLEEING, escaped));
        assertEquals(ThiefState.COOLDOWN, ThiefStateMachine.next(ThiefState.COOLDOWN, none()));
        ThiefSignals rested = new ThiefSignals(false, false, false, false, false, false, false, false, true, false);
        assertEquals(ThiefState.SCOUTING, ThiefStateMachine.next(ThiefState.COOLDOWN, rested));
    }

    @Test
    void deathEndsEverythingFromEveryState() {
        ThiefSignals dead = new ThiefSignals(true, true, true, true, true, true, true, true, true, true);
        for (ThiefState state : ThiefState.values()) {
            assertEquals(ThiefState.DEAD, ThiefStateMachine.next(state, dead), "from " + state);
        }
    }

    @Test
    void custodyHoldsTheThiefUntilSomethingOutsideReleasesIt() {
        ThiefSignals everything = new ThiefSignals(true, true, false, false, false, true, false, false, true, true);
        assertEquals(ThiefState.ARRESTED, ThiefStateMachine.next(ThiefState.ARRESTED, everything));
    }
}
