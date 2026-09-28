package dev.otectus.mcacrime.justice;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ThiefCombatPolicyTest {
    @Test void activeIdentifiedThreatIsIndependentOfPostThreatGrace() {
        assertTrue(ThiefCombatService.withinEvidenceWindow(100, 150, 0, true));
        assertFalse(ThiefCombatService.withinEvidenceWindow(100, 150, 0, false));
        assertFalse(ThiefCombatService.withinEvidenceWindow(200, 150, 100, true));
    }
    @Test void protectedTargetsAlwaysFailClosed() {
        for (var mode : ThiefCombatPolicy.values()) {
            assertFalse(ThiefCombatPolicy.exempt(mode.decide(false, true, false, false, true, true)));
            assertFalse(ThiefCombatPolicy.exempt(mode.decide(true, false, false, false, true, true)));
            assertFalse(ThiefCombatPolicy.exempt(mode.decide(true, true, true, false, true, true)));
            assertFalse(ThiefCombatPolicy.exempt(mode.decide(true, true, false, true, true, true)));
        }
    }
    @Test void policyModesHaveExplicitStableValuesAndEvidenceSemantics() {
        for (var mode : ThiefCombatPolicy.values()) assertEquals(mode, ThiefCombatPolicy.fromRule(mode.ruleValue()));
        assertFalse(ThiefCombatPolicy.exempt(ThiefCombatPolicy.NORMAL_LAW.decide(true, true, false, false, true, true)));
        assertTrue(ThiefCombatPolicy.exempt(ThiefCombatPolicy.ALL_THIEVES.decide(true, true, false, false, false, false)));
        assertFalse(ThiefCombatPolicy.exempt(ThiefCombatPolicy.EVIDENCE_REQUIRED.decide(true, true, false, false, false, false)));
        assertEquals(ThiefCombatPolicy.Reason.RECENT_EVIDENCE, ThiefCombatPolicy.EVIDENCE_REQUIRED.decide(true, true, false, false, true, false));
        assertEquals(ThiefCombatPolicy.Reason.LOCAL_REPORT, ThiefCombatPolicy.EVIDENCE_REQUIRED.decide(true, true, false, false, false, true));
    }
}
