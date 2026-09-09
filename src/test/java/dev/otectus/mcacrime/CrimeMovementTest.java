package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.CrimeNavigation;
import dev.otectus.mcacrime.ai.ReactionControlPolicy;
import dev.otectus.mcacrime.ai.VictimReactionState;
import dev.otectus.mcacrime.enforcement.JailEscortNavigation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrimeMovementTest {
    @Test void ordinaryCivilianFleeingNoLongerUsesSprintMultipliers() {
        double slowedAttribute = 0.5 * 0.65;
        assertEquals(0.625, CrimeNavigation.speed(1.25, slowedAttribute, true), 1e-9);
        assertTrue(CrimeNavigation.speed(1.25, slowedAttribute, true) * slowedAttribute < 0.25);
    }

    @Test void fastGeneticsOrEffectsCannotMultiplyCrimeNavigationWithoutABound() {
        for (double attribute : new double[] {0.5, 0.8, 1.5, 5})
            assertTrue(CrimeNavigation.speed(1.3, attribute, true) * attribute <= 0.30000001);
    }

    @Test void slowerMobsAndInvalidSpeedInputsAreHandled() {
        assertEquals(0.9, CrimeNavigation.speed(0.9, 0.2, false), 1e-9);
        assertEquals(0, CrimeNavigation.speed(Double.NaN, 0.5, true));
        assertEquals(0, CrimeNavigation.speed(-1, 0.5, true));
    }

    @Test void guardRoleExcludesAllCivilianFearAndComplianceStates() {
        for (VictimReactionState state : VictimReactionState.values()) {
            assertEquals(state == VictimReactionState.CAPTIVE,
                    ReactionControlPolicy.mayControl(true, false, state), state.name());
        }
    }

    @Test void lawfulAssignmentsTakePriorityOverEveryReaction() {
        for (VictimReactionState state : VictimReactionState.values()) {
            assertFalse(ReactionControlPolicy.mayControl(true, true, state));
            assertFalse(ReactionControlPolicy.mayControl(false, true, state));
        }
        assertTrue(ReactionControlPolicy.mayControl(false, false, VictimReactionState.COMPLYING));
    }

    @Test void guardsRefuseMuggingEvenWithoutACivilianFearController() {
        assertTrue(ReactionControlPolicy.refusesMugging(true, true, VictimReactionState.CALM));
        assertTrue(ReactionControlPolicy.refusesMugging(true, false, VictimReactionState.CALM));
        assertFalse(ReactionControlPolicy.refusesMugging(false, true, VictimReactionState.COMPLYING));
        assertTrue(ReactionControlPolicy.refusesMugging(false, true, VictimReactionState.RESISTING));
    }

    @Test void detoursAndFollowingTheGuardResetStuckDetection() {
        assertEquals(0, JailEscortNavigation.idleScans(0.5, 0, 9));
        assertEquals(0, JailEscortNavigation.idleScans(0, 0.5, 9));
        assertEquals(10, JailEscortNavigation.idleScans(0.001, 0.001, 9));
    }

    @Test void aPartialPathMustMakeProgressToBecomeAnIntermediateStop() {
        assertTrue(JailEscortNavigation.usefulSegment(2500, 900, 20));
        assertFalse(JailEscortNavigation.usefulSegment(2500, 2500, 20));
        assertFalse(JailEscortNavigation.usefulSegment(2500, 2600, 20));
        assertFalse(JailEscortNavigation.usefulSegment(2500, 900, 1));
    }
}
