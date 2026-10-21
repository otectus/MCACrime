package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.action.CrimeTargetResolver.Candidate;
import dev.otectus.mcacrime.action.CrimeTargetResolver.Decision;
import dev.otectus.mcacrime.action.CrimeTargetResolver.Kind;
import dev.otectus.mcacrime.action.CrimeTargetResolver.Mode;
import dev.otectus.mcacrime.action.CrimeTargetResolver.Outcome;
import dev.otectus.mcacrime.action.CrimeTargetResolver.Policy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Command targeting as a pure rule (0.8.0, workstream A).
 *
 * <p>The 0.7.x commands chose "the nearest MCA villager in an inflated box", which on a real server
 * (recorded by the usability probe against the 0.7.9 jar) changed a bystander behind the operator, a
 * villager behind a wall, and one 10.6 blocks away in the box's corner; and reported a vanilla villager,
 * a zombie villager and an empty field with the same "No MCA villager within 8 blocks". These cases pin
 * the replacement rule: aim first, explicit exactly, one fallback for operators only, a spherical
 * distance with exact boundaries, and a distinct outcome for every way it can fail.
 */
class CrimeTargetResolverTest {

    private static Candidate villager(double distance, boolean visible) {
        return new Candidate(true, Kind.MCA_VILLAGER, distance * distance, visible, true);
    }

    private static Candidate other(Kind kind, double distance) {
        return new Candidate(false, kind, distance * distance, true, true);
    }

    @Test
    void theAimedVillagerWinsOverACloserBystander() {
        Decision decision = CrimeTargetResolver.decide(Policy.OPERATOR, true, villager(5.0, true),
                List.of(villager(1.5, true)));
        assertEquals(Outcome.SELECTED, decision.outcome());
        assertEquals(Mode.CROSSHAIR, decision.mode(), "the crosshair, not the nearest, decides");
    }

    @Test
    void anAimedWrongKindIsReportedAndNothingElseIsChosen() {
        for (Kind kind : List.of(Kind.VANILLA_VILLAGER, Kind.MCA_ZOMBIE_VILLAGER, Kind.ZOMBIE_VILLAGER,
                Kind.WANDERING_TRADER, Kind.PLAYER, Kind.OTHER)) {
            Decision decision = CrimeTargetResolver.decide(Policy.OPERATOR, true, other(kind, 3.0),
                    List.of(villager(2.0, true)));
            assertEquals(Outcome.NOT_SUPPORTED, decision.outcome(), kind + " is explained, not replaced");
            assertEquals(-1, decision.index(), "no villager beside it is picked instead");
        }
    }

    @Test
    void withNothingAimedTheOperatorFallbackTakesOnlyASingleVisibleCandidate() {
        Decision one = CrimeTargetResolver.decide(Policy.OPERATOR, true, null, List.of(villager(6.0, true)));
        assertEquals(Outcome.SELECTED, one.outcome());
        assertEquals(Mode.SOLE_CANDIDATE, one.mode());
        assertEquals(0, one.index());

        Decision two = CrimeTargetResolver.decide(Policy.OPERATOR, true, null,
                List.of(villager(3.0, true), villager(6.0, true)));
        assertEquals(Outcome.AMBIGUOUS, two.outcome());
        assertEquals(2, two.candidates());
    }

    @Test
    void aVillagerBehindAWallIsNeverChosenAndIsNamedAsObstructed() {
        Decision hidden = CrimeTargetResolver.decide(Policy.OPERATOR, true, null, List.of(villager(3.0, false)));
        assertEquals(Outcome.OBSTRUCTED, hidden.outcome());
        Decision hiddenAndVisible = CrimeTargetResolver.decide(Policy.OPERATOR, true, null,
                List.of(villager(2.0, false), villager(7.0, true)));
        assertEquals(Outcome.SELECTED, hiddenAndVisible.outcome(), "the hidden one does not make it ambiguous");
        assertEquals(1, hiddenAndVisible.index());
    }

    @Test
    void theRangeIsASphereWithAnInclusiveBoundary() {
        assertEquals(Outcome.SELECTED, CrimeTargetResolver.decide(Policy.OPERATOR, true, null,
                List.of(villager(8.0, true))).outcome(), "exactly eight blocks is in range");
        assertEquals(Outcome.NO_ENTITY, CrimeTargetResolver.decide(Policy.OPERATOR, true, null,
                List.of(villager(8.0001, true))).outcome(), "just past eight is not");
        // The 0.7.x box corner: 7.5 blocks on two axes is 10.6 blocks away and must not be selected.
        double corner = Math.sqrt(7.5 * 7.5 + 7.5 * 7.5);
        assertEquals(Outcome.NO_ENTITY, CrimeTargetResolver.decide(Policy.OPERATOR, true, null,
                List.of(villager(corner, true))).outcome());
        assertEquals(Outcome.OUT_OF_REACH, CrimeTargetResolver.decide(Policy.GAMEPLAY, true,
                villager(4.0001, true), List.of()).outcome(), "aimed just past gameplay reach");
        assertEquals(Outcome.SELECTED, CrimeTargetResolver.decide(Policy.GAMEPLAY, true,
                villager(4.0, true), List.of()).outcome(), "aimed at exactly four blocks");
    }

    @Test
    void gameplayNeverFallsBackToANearbyVictimOrGuard() {
        Decision decision = CrimeTargetResolver.decide(Policy.GAMEPLAY, true, null, List.of(villager(1.0, true)));
        assertEquals(Outcome.NO_ENTITY, decision.outcome());
        assertEquals(-1, decision.index());
    }

    @Test
    void gameplayNeedsAClearViewOfTheAimedVillager() {
        assertEquals(Outcome.OBSTRUCTED, CrimeTargetResolver.decide(Policy.GAMEPLAY, true,
                villager(2.0, false), List.of()).outcome());
    }

    @Test
    void anUnboundIdentityIsItsOwnAnswerAheadOfEverythingElse() {
        assertEquals(Outcome.RECOGNITION_UNAVAILABLE, CrimeTargetResolver.decide(Policy.OPERATOR, false,
                villager(2.0, true), List.of(villager(2.0, true))).outcome());
        assertEquals(Outcome.RECOGNITION_UNAVAILABLE, CrimeTargetResolver.decide(Policy.GAMEPLAY, false,
                null, List.of()).outcome());
    }

    @Test
    void aDeadAimedEntityIsGoneRatherThanAWrongKind() {
        Candidate dead = new Candidate(true, Kind.MCA_VILLAGER, 4.0, true, false);
        assertEquals(Outcome.GONE, CrimeTargetResolver.decide(Policy.OPERATOR, true, dead, List.of()).outcome());
    }

    @Test
    void wrongKindsInTheNeighbourhoodNeverCountAsCandidates() {
        Decision decision = CrimeTargetResolver.decide(Policy.OPERATOR, true, null,
                List.of(other(Kind.VANILLA_VILLAGER, 2.0), other(Kind.MCA_ZOMBIE_VILLAGER, 3.0)));
        assertEquals(Outcome.NO_ENTITY, decision.outcome());
    }

    @Test
    void thePoliciesKeepTheirDocumentedRanges() {
        assertEquals("4", Policy.GAMEPLAY.rangeText());
        assertEquals("8", Policy.OPERATOR.rangeText());
        assertEquals("10", Policy.DIAGNOSTIC.rangeText());
        assertEquals(16.0, Policy.GAMEPLAY.rangeSqr(), "the action services' own REACH_SQR");
    }
}
