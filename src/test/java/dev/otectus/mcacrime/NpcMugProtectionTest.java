package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.thief.MugTargetSelector;
import dev.otectus.mcacrime.ai.thief.GuardRisk;
import dev.otectus.mcacrime.ai.thief.ThiefPolicy;
import dev.otectus.mcacrime.ai.thief.ThiefState;
import dev.otectus.mcacrime.ai.thief.ThiefStateMachine;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcMugProtectionTest {
    @Test
    void defaultProtectsExactlyFiftyAndHigherWithoutProtectingNeutralOrNegativeHearts() {
        int threshold = McaCrimeConfig.COMMON.thiefMugProtectionHearts.getDefault();
        assertEquals(50, threshold);
        for (int hearts : new int[]{Integer.MIN_VALUE, -50, 0, 49}) {
            assertFalse(NpcMuggingService.relationshipProtects(hearts, threshold));
        }
        for (int hearts : new int[]{50, 51, 100, Integer.MAX_VALUE}) {
            assertTrue(NpcMuggingService.relationshipProtects(hearts, threshold));
        }
    }

    @Test
    void customThresholdIsInclusiveAndZeroProtectsNeutralRelationships() {
        assertFalse(NpcMuggingService.relationshipProtects(74, 75));
        assertTrue(NpcMuggingService.relationshipProtects(75, 75));
        assertTrue(NpcMuggingService.relationshipProtects(76, 75));
        assertFalse(NpcMuggingService.relationshipProtects(-1, 0));
        assertTrue(NpcMuggingService.relationshipProtects(0, 0));
        assertTrue(NpcMuggingService.relationshipProtects(50, 0));
        assertFalse(NpcMuggingService.relationshipProtects(999, 1000));
        assertTrue(NpcMuggingService.relationshipProtects(1000, 1000));
    }

    @Test
    void disabledProtectionDoesNotMakeNegativeHeartsAnAccidentalThreshold() {
        for (int hearts : new int[]{Integer.MIN_VALUE, -50, -1, 0, 50, Integer.MAX_VALUE}) {
            assertFalse(NpcMuggingService.relationshipProtects(hearts, -1));
        }
    }

    @Test
    void relationshipAndConfigChangesAreReadAsCurrentFacts() {
        assertFalse(NpcMuggingService.relationshipProtects(49, 50));
        assertTrue(NpcMuggingService.relationshipProtects(50, 50));
        assertFalse(NpcMuggingService.relationshipProtects(50, 75));
        assertTrue(NpcMuggingService.relationshipProtects(50, 25));
    }

    @Test
    void protectedVictimCannotWinSelectionWithWealthOrProximity() {
        UUID friend = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        var protectedCandidate = new MugTargetSelector.VictimCandidate(friend, 1, false, false,
                false, false, NpcMuggingService.relationshipProtects(50, 50), true, 0, Long.MAX_VALUE, 0);
        var eligibleCandidate = new MugTargetSelector.VictimCandidate(stranger, 15, false, false,
                false, false, NpcMuggingService.relationshipProtects(49, 50), true, 3, 0, 0);
        var policy = new ThiefPolicy(80, 12000, 30, 20, 16, 8, 0.6);
        assertEquals(stranger, MugTargetSelector.select(List.of(protectedCandidate, eligibleCandidate),
                GuardRisk.none(), policy).orElseThrow().id());
    }

    @Test
    void aNewlyProtectedVictimCannotAdvanceAnApproachOrFinishAMug() {
        var protectedVictim = new ThiefStateMachine.ThiefSignals(true, true, false, false,
                true, true, false, false, true, false);
        assertEquals(ThiefState.SCOUTING, ThiefStateMachine.next(ThiefState.SCOUTING, protectedVictim));
        assertEquals(ThiefState.SCOUTING, ThiefStateMachine.next(ThiefState.APPROACHING, protectedVictim));
        assertEquals(ThiefState.FLEEING, ThiefStateMachine.next(ThiefState.THREATENING, protectedVictim));
        assertEquals(ThiefState.FLEEING, ThiefStateMachine.next(ThiefState.MUGGING, protectedVictim));
    }
}
