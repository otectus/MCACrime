package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.thief.GuardRisk;
import dev.otectus.mcacrime.ai.thief.GuardRiskEvaluator;
import dev.otectus.mcacrime.ai.thief.GuardRiskEvaluator.GuardSighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard risk scoring (0.5.1, spec §"Thieves must avoid guards").
 *
 * <p>The property under test is the one the spec cares about: sight matters more than distance. A
 * thief that scored purely on proximity would treat a guard round a corner exactly like a guard
 * looking straight at it, and would then never use an alley — which is the difference between a
 * mugging that feels like a failure by the thief and one that feels like the routine outcome of an
 * NPC robbing people beside the police station.
 */
class GuardRiskEvaluatorTest {

    private static final double AVOID = 16.0;
    private static final double HARD = 8.0;
    /** The shipped {@code guardRiskAbortThreshold}. */
    private static final double THRESHOLD = 0.6;

    private static GuardRisk evaluate(GuardSighting... sightings) {
        return GuardRiskEvaluator.evaluate(List.of(sightings), AVOID, HARD);
    }

    @Test
    void noGuardsIsNoRisk() {
        GuardRisk risk = evaluate();
        assertEquals(0, risk.nearbyCount());
        assertEquals(0.0, risk.riskScore());
        assertFalse(risk.exceeds(THRESHOLD));
    }

    @Test
    void aGuardInsideTheHardRadiusWithLineOfSightIsAtLeastCertain() {
        GuardRisk risk = evaluate(new GuardSighting(6.0, true));
        assertTrue(risk.riskScore() >= 1.0,
                "a guard six blocks away and watching must be past every threshold, was " + risk.riskScore());
        assertTrue(risk.hasLineOfSight());
        assertTrue(risk.exceeds(THRESHOLD));
    }

    @Test
    void aDistantObscuredGuardStaysBelowTheAbortThreshold() {
        GuardRisk risk = evaluate(new GuardSighting(14.0, false));
        assertFalse(risk.exceeds(THRESHOLD),
                "a guard fourteen blocks away who cannot see the thief should not call off a mugging, "
                        + "score was " + risk.riskScore());
    }

    @Test
    void beingSeenCostsMoreThanBeingClose() {
        double seenFar = evaluate(new GuardSighting(12.0, true)).riskScore();
        double unseenNear = evaluate(new GuardSighting(9.0, false)).riskScore();
        assertTrue(seenFar > unseenNear,
                "line of sight must outweigh three blocks of distance: " + seenFar + " vs " + unseenNear);
    }

    @Test
    void guardsBeyondTheAvoidRadiusAreIgnoredEntirely() {
        GuardRisk risk = evaluate(new GuardSighting(20.0, true), new GuardSighting(40.0, true));
        assertEquals(0, risk.nearbyCount());
        assertEquals(0.0, risk.riskScore());
    }

    @Test
    void moreGuardsScoreHigherThanOne() {
        double one = evaluate(new GuardSighting(12.0, false)).riskScore();
        double three = evaluate(new GuardSighting(12.0, false), new GuardSighting(13.0, false),
                new GuardSighting(14.0, false)).riskScore();
        assertTrue(three > one, "a crowd of guards is worse than one: " + three + " vs " + one);
    }
}
