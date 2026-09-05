package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.ArmedResolver;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.ai.ThreatComplianceDecider;
import dev.otectus.mcacrime.ai.ThreatComplianceDecider.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The threat-compliance ordering (0.5.1, spec §"threat compliance").
 *
 * <p>The assertion that matters most is the negative one: nothing about being armed, being close, or
 * being frightening produces {@link Decision#COMPLY}. Only an open coercive session does. A villager
 * who freezes because a player walked past with a sword is the bug this decider exists to make
 * impossible, so it is tested directly rather than left to the caller.
 */
class ThreatComplianceDeciderTest {

    private static final ArmedResolver.ArmedStatus ARMED =
            new ArmedResolver.ArmedStatus(true, ArmedResolver.WeaponSource.ROLE, null, null);
    private static final ArmedResolver.ArmedStatus UNARMED = ArmedResolver.ArmedStatus.unarmed();

    private static Decision decide(ArmedResolver.ArmedStatus armed, boolean session, double resistance,
                                   double helpScore, boolean helpNearby, boolean armedCanResist,
                                   boolean freeze) {
        return ThreatComplianceDecider.decide(armed, session, resistance, helpScore, helpNearby,
                armedCanResist, freeze, 0.6, 0.5);
    }

    @Test
    void anArmedVillagerResistsWhetherOrNotSomebodyIsRobbingThem() {
        assertEquals(Decision.RESIST, decide(ARMED, true, 0.0, 0.0, true, true, true));
        assertEquals(Decision.RESIST, decide(ARMED, false, 0.0, 0.0, true, true, true));
    }

    @Test
    void anUnarmedVictimOfALiveCoerciveSessionComplies() {
        assertEquals(Decision.COMPLY, decide(UNARMED, true, 0.0, 0.0, false, true, true));
    }

    @Test
    void withoutASessionThereIsNoComplianceHoweverFrightenedTheyAre() {
        // The whole point: proximity to an armed player is not a threat, being robbed is.
        assertEquals(Decision.FLEE, decide(UNARMED, false, 0.0, 0.0, false, true, true));
    }

    @Test
    void freezingCanBeTurnedOffAndThenTheyRunInstead() {
        assertEquals(Decision.FLEE, decide(UNARMED, true, 0.0, 0.0, false, true, false));
    }

    @Test
    void withoutASessionTheExistingBraveryAndHelpThresholdsStillDecide() {
        assertEquals(Decision.RESIST, decide(UNARMED, false, 0.6, 0.9, true, true, true));
        assertEquals(Decision.SEEK_HELP, decide(UNARMED, false, 0.59, 0.5, true, true, true));
        assertEquals(Decision.FLEE, decide(UNARMED, false, 0.59, 0.5, false, true, true),
                "there is nobody to fetch, so fetching somebody is not an option");
        assertEquals(Decision.FLEE, decide(UNARMED, false, 0.59, 0.49, true, true, true));
    }

    @Test
    void turningOffArmedResistanceMakesEvenAGuardComply() {
        assertEquals(Decision.COMPLY, decide(ARMED, true, 1.0, 1.0, true, false, true));
    }

    @Test
    void aNullStatusIsTreatedAsUnarmedRatherThanThrowing() {
        assertEquals(Decision.COMPLY, decide(null, true, 0.0, 0.0, false, true, true));
    }

    @Test
    void aLiveCoerciveSessionIsDecidedOnTheSpotWhileAnObservationStillGetsItsBeat() {
        // The deliberation beat is for ambiguity. Being robbed is not ambiguous, and half a second of
        // hesitation there is half a second of the victim walking away before anything holds them.
        assertEquals(0, CrimeReactionService.decisionDelayFor(true));
        assertEquals(10, CrimeReactionService.decisionDelayFor(false));
    }
}
