package dev.otectus.mcacrime;

import dev.otectus.mcacrime.effect.SandRecovery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule that keeps sand from becoming permanent blindness (0.7.2 §13.6, SAND-09, SAND-10). */
class SandRecoveryTest {

    private static final int DURATION = 80;
    private static final int RECOVERY = 60;

    @Test
    void aSecondBottleDuringTheEffectCannotExtendIt() {
        SandRecovery.State state = SandRecovery.applied(1000L, DURATION, RECOVERY);
        assertFalse(SandRecovery.canApply(state, 1000L));
        assertFalse(SandRecovery.canApply(state, 1000L + DURATION - 1));
        assertEquals(1000L + DURATION, state.activeUntil());
    }

    @Test
    void theWindowAfterTheEffectRefusesEveryThrowerNotJustTheFirst() {
        // The record belongs to the target, not to a (thrower, target) pair, which is the whole point:
        // two players alternating bottles is the attack this closes.
        SandRecovery.State state = SandRecovery.applied(0L, DURATION, RECOVERY);
        assertFalse(SandRecovery.canApply(state, DURATION), "sight has just returned, protection has not");
        assertFalse(SandRecovery.canApply(state, DURATION + RECOVERY - 1));
        assertTrue(SandRecovery.canApply(state, DURATION + RECOVERY));
    }

    @Test
    void theRecoveryWindowStartsWhenSightReturnsNotWhenTheBottleLanded() {
        SandRecovery.State state = SandRecovery.applied(500L, DURATION, RECOVERY);
        assertEquals(500L + DURATION + RECOVERY, state.recoveryUntil());
    }

    @Test
    void anEarlyCureBuysSightBackButNotASecondBottle() {
        SandRecovery.State cured = SandRecovery.cured(1200L, RECOVERY);
        assertEquals(1200L, cured.activeUntil(), "the effect is over the instant it is cured");
        assertFalse(SandRecovery.canApply(cured, 1200L));
        assertFalse(SandRecovery.canApply(cured, 1200L + RECOVERY - 1));
        assertTrue(SandRecovery.canApply(cured, 1200L + RECOVERY));
    }

    @Test
    void aRecordThatHasFullyElapsedIsClearAndForgettable() {
        SandRecovery.State state = SandRecovery.applied(0L, DURATION, RECOVERY);
        long after = DURATION + RECOVERY;
        assertTrue(SandRecovery.reconcile(state, after).isClear());
        assertTrue(SandRecovery.forgettable(state, after));
        assertFalse(SandRecovery.forgettable(state, after - 1));
    }

    @Test
    void aTimestampFromBeforeAWorldClockRollbackIsDroppedRatherThanBlindingForever() {
        // /time set, a restored backup, or a world whose game time went backwards. A stored "blinded
        // until tick 9,000,000" read at tick 40 must not mean a permanently blind villager.
        SandRecovery.State absurd = new SandRecovery.State(9_000_000L, 9_000_060L);
        assertTrue(SandRecovery.reconcile(absurd, 40L).isClear());
        assertTrue(SandRecovery.canApply(absurd, 40L));
    }

    @Test
    void aPlausibleFutureTimestampSurvivesReloadUnchanged() {
        SandRecovery.State state = SandRecovery.applied(10_000L, DURATION, RECOVERY);
        assertEquals(state, SandRecovery.reconcile(state, 10_010L),
                "a restart inside the window still refuses the next bottle");
        assertFalse(SandRecovery.canApply(SandRecovery.reconcile(state, 10_010L), 10_010L));
    }

    @Test
    void aMissingRecordIsClearRatherThanAnError() {
        assertEquals(SandRecovery.State.CLEAR, SandRecovery.reconcile(null, 5L));
        assertTrue(SandRecovery.canApply(SandRecovery.State.CLEAR, 0L));
    }

    @Test
    void aZeroRecoveryConfigurationStillEndsTheEffectCleanly() {
        SandRecovery.State state = SandRecovery.applied(0L, DURATION, 0);
        assertEquals(DURATION, state.expiresAt());
        assertTrue(SandRecovery.canApply(state, DURATION));
    }
}
