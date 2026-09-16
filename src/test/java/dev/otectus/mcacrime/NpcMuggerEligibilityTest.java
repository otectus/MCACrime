package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.NpcMuggerEligibility;
import dev.otectus.mcacrime.job.NpcMuggerEligibility.Context;
import dev.otectus.mcacrime.job.NpcMuggerEligibility.Facts;
import dev.otectus.mcacrime.job.NpcMuggerEligibility.Result;
import dev.otectus.mcacrime.job.NpcMuggerEligibilityReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that a guard is never a mugger, asserted where it is decided (0.7.2, spec §3).
 *
 * <p>Two things are being pinned, and neither is "guards are excluded" on its own.
 *
 * <p>The first is <em>ordering</em>. Responder identity is read before any condition that can change
 * back, because the bug this replaces came from asking "is this guard awake and armed and on duty"
 * where the question was "is this villager law". A sleeping guard is still law.
 *
 * <p>The second is that an unreadable classification is its own answer. When MCA cannot be asked, the
 * result must be UNKNOWN and must fail closed — never the cheerful "not a guard, then" that let a
 * guard acquire a Thief record while MCA's profession handle was unbound.
 */
class NpcMuggerEligibilityTest {

    /** An ordinary adult MCA villager nobody has any objection to. */
    private static Facts civilian() {
        return new Facts(true, true, true, false, true, false, false);
    }

    /** The same villager, already recorded as a thief. */
    private static Facts thief() {
        return new Facts(true, true, true, false, true, true, true);
    }

    /** Law: an MCA guard, an MCA archer, or a configured responder entity — all one fact here. */
    private static Facts responder() {
        return new Facts(true, true, true, true, true, false, false);
    }

    // ---------------------------------------------------------------- the ordinary answers

    @Test
    void anAdultCivilianMayBeRecruited() {
        Result result = NpcMuggerEligibility.evaluate(civilian(), Context.ASSIGNMENT);
        assertTrue(result.eligible());
        assertEquals(NpcMuggerEligibilityReason.ELIGIBLE, result.reason());
    }

    /** The one difference between the two contexts, in both directions. */
    @Test
    void assignmentDoesNotRequireAThiefRecordButExecutionDoes() {
        assertTrue(NpcMuggerEligibility.evaluate(civilian(), Context.ASSIGNMENT).eligible());
        assertEquals(NpcMuggerEligibilityReason.NOT_A_THIEF,
                NpcMuggerEligibility.evaluate(civilian(), Context.EXECUTION).reason());
        assertTrue(NpcMuggerEligibility.evaluate(thief(), Context.EXECUTION).eligible());
    }

    // ---------------------------------------------------------------- law identity

    @Test
    void aResponderIsRejectedInEveryContext() {
        for (Context context : Context.values()) {
            Result result = NpcMuggerEligibility.evaluate(responder(), context);
            assertFalse(result.eligible(), "a responder passed " + context);
            assertEquals(NpcMuggerEligibilityReason.RESPONDER, result.reason());
        }
    }

    /**
     * Availability is a different question from identity, and mixing them is the original defect.
     * These facts say nothing about being awake or armed on purpose: if the policy ever needed them,
     * it would be asking {@code isAvailableResponder} rather than {@code isResponder}.
     */
    @Test
    void aSleepingOrUnarmedGuardIsStillLaw() {
        // The temporary conditions live outside these facts; the only signal is identity, and identity
        // alone must be enough to reject.
        assertEquals(NpcMuggerEligibilityReason.RESPONDER,
                NpcMuggerEligibility.evaluate(responder(), Context.ASSIGNMENT).reason());
    }

    /** Identity outranks every other objection, so the reason an operator reads is the real one. */
    @Test
    void responderIdentityIsReportedAheadOfTemporaryFacts() {
        Facts unloadedResponder = new Facts(false, false, false, true, false, false, false);
        assertEquals(NpcMuggerEligibilityReason.RESPONDER,
                NpcMuggerEligibility.evaluate(unloadedResponder, Context.ASSIGNMENT).reason());
    }

    /** A responder carrying a stale Thief record is the defect itself, and is named rather than skipped. */
    @Test
    void aResponderHoldingACriminalRecordIsContradictory() {
        Facts stale = new Facts(true, true, true, true, true, true, true);
        assertTrue(NpcMuggerEligibility.contradictory(stale));
        assertEquals(NpcMuggerEligibilityReason.RESPONDER,
                NpcMuggerEligibility.evaluate(stale, Context.EXECUTION).reason());

        assertFalse(NpcMuggerEligibility.contradictory(thief()), "an ordinary thief is not contradictory");
        assertFalse(NpcMuggerEligibility.contradictory(responder()), "a guard without a record is fine");
        assertFalse(NpcMuggerEligibility.contradictory(null));
    }

    // ---------------------------------------------------------------- unknown, not "no"

    @Test
    void anUnreadableClassificationFailsClosedAndIsNamedAsUnknown() {
        Facts unclassifiable = new Facts(true, true, false, false, true, false, false);
        for (Context context : Context.values()) {
            Result result = NpcMuggerEligibility.evaluate(unclassifiable, context);
            assertFalse(result.eligible(), "an unclassifiable villager passed " + context);
            assertEquals(NpcMuggerEligibilityReason.CLASSIFICATION_UNAVAILABLE, result.reason(),
                    "an unavailable binding must never be reported as 'not a guard'");
        }
    }

    /** With MCA unbound nothing reads: no villager test, no profession, no age. Still not eligible. */
    @Test
    void anAbsentMcaBindingNeverProducesAnEligibleActor() {
        Facts mcaAbsent = new Facts(true, false, false, false, false, true, true);
        assertEquals(NpcMuggerEligibilityReason.CLASSIFICATION_UNAVAILABLE,
                NpcMuggerEligibility.evaluate(mcaAbsent, Context.EXECUTION).reason());
    }

    @Test
    void nullFactsOrContextAreUnknownRatherThanPermissive() {
        assertEquals(NpcMuggerEligibilityReason.CLASSIFICATION_UNAVAILABLE,
                NpcMuggerEligibility.evaluate(null, Context.ASSIGNMENT).reason());
        assertEquals(NpcMuggerEligibilityReason.CLASSIFICATION_UNAVAILABLE,
                NpcMuggerEligibility.evaluate(civilian(), null).reason());
    }

    // ---------------------------------------------------------------- the remaining exclusions

    @Test
    void anUnloadedVillagerIsNotActivated() {
        Result result = NpcMuggerEligibility.evaluate(Facts.unloaded(true, true), Context.ASSIGNMENT);
        assertFalse(result.eligible());
        assertEquals(NpcMuggerEligibilityReason.NOT_LOADED, result.reason());
    }

    @Test
    void nonVillagersAndChildrenAreExcluded() {
        Facts notAVillager = new Facts(true, false, true, false, true, false, false);
        assertEquals(NpcMuggerEligibilityReason.NOT_A_VILLAGER,
                NpcMuggerEligibility.evaluate(notAVillager, Context.ASSIGNMENT).reason());

        Facts child = new Facts(true, true, true, false, false, false, false);
        assertEquals(NpcMuggerEligibilityReason.NOT_ADULT,
                NpcMuggerEligibility.evaluate(child, Context.ASSIGNMENT).reason());
    }

    /**
     * A child who already holds a record may still be driven. Recruiting one is forbidden; retiring an
     * existing record is the sweep's job, and refusing to tick a grown-up thief who read as a child for
     * one pass would strand an open session instead.
     */
    @Test
    void theAgeCheckBelongsToRecruitmentOnly() {
        Facts youngThief = new Facts(true, true, true, false, false, true, true);
        assertTrue(NpcMuggerEligibility.evaluate(youngThief, Context.EXECUTION).eligible());
    }

    // ---------------------------------------------------------------- promotion, the other direction

    @Test
    void aCriminalRecordBlocksGuardPromotion() {
        assertTrue(NpcMuggerEligibility.guardPromotionBlocked(true));
        assertFalse(NpcMuggerEligibility.guardPromotionBlocked(false));
    }

    /**
     * The policy holds no state, so a reload that changes {@code responderEntities} changes the answer
     * on the very next call rather than at the next restart.
     */
    @Test
    void theSameVillagerReEvaluatesWhenTheFactsChange() {
        Facts before = thief();
        assertTrue(NpcMuggerEligibility.evaluate(before, Context.EXECUTION).eligible());

        Facts afterReload = new Facts(true, true, true, true, true, true, true);
        assertEquals(NpcMuggerEligibilityReason.RESPONDER,
                NpcMuggerEligibility.evaluate(afterReload, Context.EXECUTION).reason());
    }
}
