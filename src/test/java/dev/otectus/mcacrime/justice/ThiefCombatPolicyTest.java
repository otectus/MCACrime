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

    /** 0.7.7: being held up is the thief's aggression, so every policy lets the victim fight it off. */
    @Test void aMuggingVictimMayFightBackUnderEveryPolicy() {
        for (var mode : ThiefCombatPolicy.values()) {
            // Even with no Thief record left and no evidence at all: the threat itself is the basis.
            var reason = mode.decide(false, true, false, false, false, false, true, true);
            assertEquals(ThiefCombatPolicy.Reason.ACTIVE_THREAT, reason, mode.name());
            assertTrue(ThiefCombatPolicy.exempt(reason), mode.name());
        }
    }

    /** Killing the mugger is lawful where the policy already lets you kill a Thief, and not under normal law. */
    @Test void lethalDefenceFollowsThePolicy() {
        var threat = ThiefCombatPolicy.Reason.ACTIVE_THREAT;
        assertFalse(ThiefCombatPolicy.NORMAL_LAW.exemptsKill(threat));
        assertTrue(ThiefCombatPolicy.EVIDENCE_REQUIRED.exemptsKill(threat));
        assertTrue(ThiefCombatPolicy.ALL_THIEVES.exemptsKill(threat));
        // Every other reason keeps its old answer for a killing as well.
        for (var mode : ThiefCombatPolicy.values()) {
            for (var reason : ThiefCombatPolicy.Reason.values()) {
                if (reason != threat) assertEquals(ThiefCombatPolicy.exempt(reason), mode.exemptsKill(reason),
                        mode + "/" + reason);
            }
        }
        var decision = new ThiefCombatDecision(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), "damage",
                ThiefCombatPolicy.NORMAL_LAW.ruleValue(), threat, null);
        assertTrue(decision.exempt());
        assertFalse(decision.exemptsKill());
        assertTrue(decision.answersThreat());
    }

    /** A threat never licenses force on a child, a guard or somebody already restrained. */
    @Test void aThreatNeverLicensesForceOnAProtectedTarget() {
        for (var mode : ThiefCombatPolicy.values()) {
            assertNotEquals(ThiefCombatPolicy.Reason.ACTIVE_THREAT, mode.decide(true, true, false, true, false, false, true, true),
                    "restrained " + mode);
            assertNotEquals(ThiefCombatPolicy.Reason.ACTIVE_THREAT, mode.decide(true, true, true, false, false, false, true, true),
                    "responder " + mode);
            assertNotEquals(ThiefCombatPolicy.Reason.ACTIVE_THREAT, mode.decide(false, true, false, false, false, false, true, false),
                    "child " + mode);
            assertFalse(ThiefCombatPolicy.exempt(mode.decide(false, true, false, true, false, false, true, true)),
                    "a restrained former mugger is a prisoner under " + mode);
        }
    }

    /** 0.7.7: MCA's Outlaw may be fought and killed under every policy, with no evidence, report or record. */
    @Test void anOutlawIsFairGameUnderEveryPolicyIncludingKilling() {
        for (var mode : ThiefCombatPolicy.values()) {
            var reason = mode.decide(false, false, false, false, false, false, false, true, true);
            assertEquals(ThiefCombatPolicy.Reason.OUTLAW, reason, mode.name());
            assertTrue(ThiefCombatPolicy.exempt(reason), "harming an outlaw under " + mode);
            assertTrue(mode.exemptsKill(reason), "killing an outlaw under " + mode);
        }
        var decision = new ThiefCombatDecision(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), "death",
                ThiefCombatPolicy.NORMAL_LAW.ruleValue(), ThiefCombatPolicy.Reason.OUTLAW, null);
        assertTrue(decision.exempt());
        assertTrue(decision.exemptsKill(), "normal law gives Thieves nothing, but an outlaw is not protected");
        assertFalse(decision.answersThreat(), "an outlaw fight has no aggressor rule to rewrite");
    }

    /** The outlaw grant is wider than a threat, so it is asked first and wins when both hold. */
    @Test void anOutlawWhoIsAlsoThreateningIsJudgedAsAnOutlaw() {
        var reason = ThiefCombatPolicy.NORMAL_LAW.decide(false, true, false, false, false, false, true, true, true);
        assertEquals(ThiefCombatPolicy.Reason.OUTLAW, reason);
        assertTrue(ThiefCombatPolicy.NORMAL_LAW.exemptsKill(reason), "killing is lawful, not excessive force");
    }

    /** Guards, the restrained and children stay protected whatever their profession says. */
    @Test void anOutlawStillYieldsToTheThreeProtections() {
        for (var mode : ThiefCombatPolicy.values()) {
            assertNotEquals(ThiefCombatPolicy.Reason.OUTLAW,
                    mode.decide(false, true, false, true, false, false, false, true, true), "restrained " + mode);
            assertNotEquals(ThiefCombatPolicy.Reason.OUTLAW,
                    mode.decide(false, true, true, false, false, false, false, true, true), "responder " + mode);
            assertNotEquals(ThiefCombatPolicy.Reason.OUTLAW,
                    mode.decide(false, true, false, false, false, false, false, false, true), "child " + mode);
            assertFalse(ThiefCombatPolicy.exempt(mode.decide(false, true, false, true, false, false, false, true, true)),
                    "a captured outlaw is a prisoner under " + mode);
        }
    }

    /** With outlawCombatLegal off the outlaw input is false, and every answer is the pre-0.7.7 one. */
    @Test void withoutTheOutlawInputEveryDecisionIsUnchanged() {
        boolean[] flags = {false, true};
        for (var mode : ThiefCombatPolicy.values())
            for (boolean thief : flags) for (boolean role : flags) for (boolean responder : flags)
                for (boolean restrained : flags) for (boolean threatened : flags) for (boolean adult : flags)
                    assertEquals(mode.decide(thief, role, responder, restrained, false, false, threatened, adult),
                            mode.decide(thief, role, responder, restrained, false, false, threatened, adult, false),
                            mode + " " + thief + role + responder + restrained + threatened + adult);
    }
}
