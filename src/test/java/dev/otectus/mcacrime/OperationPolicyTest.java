package dev.otectus.mcacrime;

import dev.otectus.mcacrime.activity.CrimeActivityOperation;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.activity.OperationPolicy;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coordination table, one assertion per row.
 *
 * <p>Written as exact set equality rather than as spot checks on purpose. The value of a table like
 * this is that somebody can read the whole rule in one place; a test that only checked the
 * interesting cells would let a row drift without anybody noticing, which is exactly how a
 * coordination layer starts quietly failing to coordinate.
 */
class OperationPolicyTest {

    private static Set<CrimeActivityOperation> ops(CrimeActivityOperation... operations) {
        return operations.length == 0 ? Set.of() : Set.of(operations);
    }

    private static void row(CrimeActivityView.Kind kind, Set<CrimeActivityOperation> yields) {
        assertEquals(yields, OperationPolicy.yielded(kind), kind + " yield row");

        EnumSet<CrimeActivityOperation> allowed = EnumSet.allOf(CrimeActivityOperation.class);
        allowed.removeAll(yields);
        assertEquals(Set.copyOf(allowed), Set.copyOf(OperationPolicy.allowed(kind)),
                kind + ": allowed must be the exact complement of yielded");

        for (CrimeActivityOperation operation : CrimeActivityOperation.values()) {
            assertEquals(yields.contains(operation), OperationPolicy.yields(kind, operation),
                    kind + " / " + operation);
        }
    }

    @Test
    void holdStandsTheResponderStillAndLeavesTheirHandsAlone() {
        row(CrimeActivityView.Kind.HOLD, ops(CrimeActivityOperation.WORK_START,
                CrimeActivityOperation.REST_TRAVEL, CrimeActivityOperation.SOCIAL_WANDER));
    }

    @Test
    void challengeIsTheSameShapeAsAHold() {
        row(CrimeActivityView.Kind.CHALLENGE, ops(CrimeActivityOperation.WORK_START,
                CrimeActivityOperation.REST_TRAVEL, CrimeActivityOperation.SOCIAL_WANDER));
    }

    @Test
    void escortNeedsBothMovementAndHandState() {
        row(CrimeActivityView.Kind.ESCORT, ops(CrimeActivityOperation.WORK_START,
                CrimeActivityOperation.REST_TRAVEL, CrimeActivityOperation.SOCIAL_WANDER,
                CrimeActivityOperation.REACTION_LOCK, CrimeActivityOperation.DISPLAY_TOOL));
    }

    @Test
    void arrestNeedsBothMovementAndHandState() {
        row(CrimeActivityView.Kind.ARREST, ops(CrimeActivityOperation.WORK_START,
                CrimeActivityOperation.REST_TRAVEL, CrimeActivityOperation.SOCIAL_WANDER,
                CrimeActivityOperation.REACTION_LOCK, CrimeActivityOperation.DISPLAY_TOOL));
    }

    @Test
    void custodyAllowsAReactionButNotABorrowedTool() {
        row(CrimeActivityView.Kind.CUSTODY, ops(CrimeActivityOperation.WORK_START,
                CrimeActivityOperation.REST_TRAVEL, CrimeActivityOperation.SOCIAL_WANDER,
                CrimeActivityOperation.DISPLAY_TOOL));
        assertFalse(OperationPolicy.yields(CrimeActivityView.Kind.CUSTODY,
                        CrimeActivityOperation.REACTION_LOCK),
                "a prisoner is not going anywhere, so a reaction that freezes them costs nothing");
    }

    @Test
    void pursuitNeedsMovement() {
        row(CrimeActivityView.Kind.PURSUIT, ops(CrimeActivityOperation.WORK_START,
                CrimeActivityOperation.REST_TRAVEL, CrimeActivityOperation.SOCIAL_WANDER,
                CrimeActivityOperation.REACTION_LOCK));
    }

    @Test
    void reactionNeedsMovement() {
        row(CrimeActivityView.Kind.REACTION, ops(CrimeActivityOperation.WORK_START,
                CrimeActivityOperation.REST_TRAVEL, CrimeActivityOperation.SOCIAL_WANDER,
                CrimeActivityOperation.REACTION_LOCK));
    }

    @Test
    void thiefActionNeedsMovement() {
        row(CrimeActivityView.Kind.THIEF_ACTION, ops(CrimeActivityOperation.WORK_START,
                CrimeActivityOperation.REST_TRAVEL, CrimeActivityOperation.SOCIAL_WANDER,
                CrimeActivityOperation.REACTION_LOCK));
    }

    @Test
    void muggingNeedsMovement() {
        row(CrimeActivityView.Kind.MUGGING, ops(CrimeActivityOperation.WORK_START,
                CrimeActivityOperation.REST_TRAVEL, CrimeActivityOperation.SOCIAL_WANDER,
                CrimeActivityOperation.REACTION_LOCK));
    }

    @Test
    void everyKindStopsWorkRestAndWander() {
        for (CrimeActivityView.Kind kind : CrimeActivityView.Kind.values()) {
            assertTrue(OperationPolicy.yields(kind, CrimeActivityOperation.WORK_START),
                    kind + ": a villager MCA: Crime is acting on does not start a new job");
            assertTrue(OperationPolicy.yields(kind, CrimeActivityOperation.REST_TRAVEL),
                    kind + ": nor walk off to bed");
            assertTrue(OperationPolicy.yields(kind, CrimeActivityOperation.SOCIAL_WANDER),
                    kind + ": nor drift into a gossip circle");
        }
    }

    @Test
    void nothingYieldsWhenNothingIsClaimed() {
        assertEquals(Set.of(), OperationPolicy.yielded(null),
                "an unclaimed villager behaves exactly as they do with MCA: Crime not installed");
        assertEquals(Set.copyOf(EnumSet.allOf(CrimeActivityOperation.class)),
                Set.copyOf(OperationPolicy.allowed(null)));
        for (CrimeActivityOperation operation : CrimeActivityOperation.values()) {
            assertFalse(OperationPolicy.yields(null, operation));
        }
    }
}
