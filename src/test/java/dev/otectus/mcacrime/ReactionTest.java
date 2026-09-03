package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.ActiveCrimeReactionController;
import dev.otectus.mcacrime.ai.ReactionFactors;
import dev.otectus.mcacrime.ai.SafeDestinationSelector;
import dev.otectus.mcacrime.ai.VictimReactionState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reaction state machine's pure parts (spec §11.2–§11.5).
 *
 * <p>The controller and the destination scorer are deliberately free of entities so the rules that
 * decide whether a villager runs, fights, or fetches a guard can be asserted without a server. What
 * cannot be tested here is the navigation itself, which is the part that has to be watched in-world.
 */
class ReactionTest {

    private static final ResourceLocation DIM = new ResourceLocation("minecraft", "overworld");

    // ------------------------------------------------------------------ destination scoring

    private static SafeDestinationSelector.Candidate candidate(SafeDestinationSelector.Kind kind,
                                                               int x, double fromThreat, double pathCost) {
        return new SafeDestinationSelector.Candidate(new BlockPos(x, 64, 0), kind, fromThreat, pathCost, true, 0.0);
    }

    @Test
    void aGuardBeatsEmptyGroundEvenWhenTheGroundIsFurtherAway() {
        // This is the whole reason the old "walk eight blocks directly away" behaviour was wrong: it
        // would always pick the empty field over the guard house, and the guard house is the point.
        SafeDestinationSelector.Candidate guard = candidate(SafeDestinationSelector.Kind.RESPONDER, 1, 6.0, 6.0);
        SafeDestinationSelector.Candidate field = candidate(SafeDestinationSelector.Kind.OPEN_GROUND, 2, 24.0, 24.0);
        assertEquals(guard, SafeDestinationSelector.best(List.of(field, guard)).orElseThrow());
    }

    @Test
    void unreachableCandidatesAreNeverChosen() {
        SafeDestinationSelector.Candidate blocked = new SafeDestinationSelector.Candidate(
                new BlockPos(1, 64, 0), SafeDestinationSelector.Kind.RESPONDER, 30.0, 1.0, false, 0.0);
        SafeDestinationSelector.Candidate open = candidate(SafeDestinationSelector.Kind.OPEN_GROUND, 2, 8.0, 8.0);
        assertEquals(open, SafeDestinationSelector.best(List.of(blocked, open)).orElseThrow());
        assertTrue(SafeDestinationSelector.best(List.of(blocked)).isEmpty());
    }

    @Test
    void dangerAtTheDestinationOutweighsItsKind() {
        SafeDestinationSelector.Candidate dangerousHome = new SafeDestinationSelector.Candidate(
                new BlockPos(1, 64, 0), SafeDestinationSelector.Kind.HOME, 10.0, 4.0, true, 1.0);
        SafeDestinationSelector.Candidate safeGround = candidate(SafeDestinationSelector.Kind.OPEN_GROUND, 2, 10.0, 4.0);
        assertEquals(safeGround, SafeDestinationSelector.best(List.of(dangerousHome, safeGround)).orElseThrow());
    }

    @Test
    void extraDistanceStopsMatteringPastTheCap() {
        // Without the cap a villager would sprint across the map rather than step into a nearby house.
        double near = SafeDestinationSelector.score(candidate(SafeDestinationSelector.Kind.OPEN_GROUND, 1, 40.0, 0.0));
        double far = SafeDestinationSelector.score(candidate(SafeDestinationSelector.Kind.OPEN_GROUND, 2, 400.0, 0.0));
        assertEquals(near, far, 0.0001);
    }

    @Test
    void tiesResolveTheSameWayEveryTime() {
        SafeDestinationSelector.Candidate a = candidate(SafeDestinationSelector.Kind.OPEN_GROUND, 5, 10.0, 3.0);
        SafeDestinationSelector.Candidate b = candidate(SafeDestinationSelector.Kind.OPEN_GROUND, 9, 10.0, 3.0);
        assertEquals(SafeDestinationSelector.best(List.of(a, b)).orElseThrow(),
                SafeDestinationSelector.best(List.of(b, a)).orElseThrow(),
                "the same standoff must produce the same escape route regardless of scan order");
    }

    // ------------------------------------------------------------------ factors

    @Test
    void factorsAreDeterministicPerVillager() {
        UUID villager = UUID.randomUUID();
        assertEquals(ReactionFactors.of(villager, 0, false, true, 0, false),
                ReactionFactors.of(villager, 0, false, true, 0, false),
                "the same villager must be the same amount of brave across restarts");
    }

    @Test
    void differentVillagersDoNotAllReactIdentically() {
        // A constant fallback would be deterministic too, and would make every villager in the world
        // behave the same, which reads worse than any varied guess.
        long distinct = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(24)
                .map(id -> ReactionFactors.of(id, 0, false, true, 0, false).bravery())
                .distinct()
                .count();
        assertTrue(distinct > 12, "villager bravery should spread, not collapse to one value");
    }

    @Test
    void everyFactorStaysInRange() {
        for (int hearts = -400; hearts <= 400; hearts += 50) {
            ReactionFactors factors = ReactionFactors.of(UUID.randomUUID(), hearts, true, true, 99, true);
            assertTrue(factors.loyaltyToActor() >= 0.0F && factors.loyaltyToActor() <= 1.0F);
            assertTrue(factors.resistanceScore() >= 0.0F && factors.resistanceScore() <= 1.0F);
            assertTrue(factors.complianceScore() >= 0.0F && factors.complianceScore() <= 1.0F);
        }
    }

    @Test
    void childrenAreLessBraveThanAdults() {
        UUID villager = UUID.randomUUID();
        assertTrue(ReactionFactors.of(villager, 0, false, false, 0, false).bravery()
                        < ReactionFactors.of(villager, 0, false, true, 0, false).bravery(),
                "a child must never be braver than the same villager as an adult");
    }

    @Test
    void guardsAreBraverAndMoreCombatConfident() {
        UUID villager = UUID.randomUUID();
        ReactionFactors civilian = ReactionFactors.of(villager, 0, false, true, 0, false);
        ReactionFactors guard = ReactionFactors.of(villager, 0, true, true, 0, false);
        assertTrue(guard.bravery() > civilian.bravery());
        assertTrue(guard.combatConfidence() > civilian.combatConfidence());
    }

    @Test
    void loyaltyToFamilyPullsAgainstFightingThem() {
        UUID villager = UUID.randomUUID();
        assertTrue(ReactionFactors.of(villager, 100, false, true, 0, false).resistanceScore()
                        < ReactionFactors.of(villager, -100, false, true, 0, false).resistanceScore(),
                "you do not swing at someone you love as readily as at a stranger");
    }

    // ------------------------------------------------------------------ controller

    @Test
    void enteringTheSameStateTwiceIsNotATransition() {
        ActiveCrimeReactionController controller = new ActiveCrimeReactionController(
                UUID.randomUUID(), DIM, UUID.randomUUID(), null, 0L);
        assertTrue(controller.enter(VictimReactionState.FLEEING, 0L, 100L));
        assertFalse(controller.enter(VictimReactionState.FLEEING, 10L, 100L),
                "a repeated entry must not re-fire the public event or reset the timer");
    }

    @Test
    void enteringAStateClearsTheOldDestinationAndFailures() {
        ActiveCrimeReactionController controller = new ActiveCrimeReactionController(
                UUID.randomUUID(), DIM, UUID.randomUUID(), null, 0L);
        controller.enter(VictimReactionState.FLEEING, 0L, 100L);
        controller.setDestination(new BlockPos(1, 2, 3));
        controller.notePathFailure();

        controller.enter(VictimReactionState.HIDING, 50L, 100L);
        assertEquals(null, controller.destination());
        assertEquals(0, controller.pathFailures());
    }

    @Test
    void aTimedStateExpiresAndAnUntimedOneDoesNot() {
        ActiveCrimeReactionController controller = new ActiveCrimeReactionController(
                UUID.randomUUID(), DIM, UUID.randomUUID(), null, 0L);
        controller.enter(VictimReactionState.FLEEING, 0L, 40L);
        assertFalse(controller.timedOut(39L));
        assertTrue(controller.timedOut(40L));

        // Captivity is ended by the custody record, never by a timer in the controller.
        controller.enter(VictimReactionState.CAPTIVE, 0L, 0L);
        assertFalse(controller.timedOut(1_000_000L));
    }

    @Test
    void onlyTheStatesThatSteerClaimNavigation() {
        assertFalse(VictimReactionState.CALM.ownsBehaviour());
        assertFalse(VictimReactionState.RECOVERING.ownsBehaviour(),
                "recovery is a memory window, not a behaviour override");
        assertTrue(VictimReactionState.FLEEING.ownsNavigation());
        assertFalse(VictimReactionState.THREATENED.ownsNavigation(),
                "standing and deciding must not fight MCA for the villager's path");
        assertNotEquals(VictimReactionState.CALM, VictimReactionState.RECOVERING);
    }

    @Test
    void carryingAReportIsOnlyTheTwoDeliveryStates() {
        assertTrue(VictimReactionState.SEEKING_HELP.carryingReport());
        assertTrue(VictimReactionState.REPORTING.carryingReport());
        assertFalse(VictimReactionState.FLEEING.carryingReport());
    }
}
