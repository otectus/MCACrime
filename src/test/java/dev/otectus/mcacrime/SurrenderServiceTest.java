package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.SurrenderService;
import dev.otectus.mcacrime.economy.SurrenderService.Commit;
import dev.otectus.mcacrime.economy.SurrenderService.Decision;
import dev.otectus.mcacrime.economy.SurrenderService.SurrenderState;
import dev.otectus.mcacrime.enforcement.ArrestService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surrender is paid for on delivery (spec §6.3).
 *
 * <p>The old order dropped thirty Heat, cleared the escaped flag and printed "you surrender" before it
 * asked anybody to make the arrest. If no cell could be found, or the player was dead or spectating,
 * the arrest refused and every one of those writes stood — a discount collected for nothing, repeatable
 * by walking up to the same guard again. These tests pin the reorder: nothing is written until the law
 * has accepted the player, and a sentence already running cannot be discounted twice.
 *
 * <p>Both halves are pure, which is the only reason they can be asserted at all: the service's own
 * entry point needs a {@code ServerPlayer}, a capability, a world and a guard standing in it.
 */
class SurrenderServiceTest {

    /** 30 Heat off, jailable at 100 — the shipped defaults, near enough. */
    private static final long REDUCTION = 30L;
    private static final long JAILABLE = 100L;

    private static SurrenderState free(long heat) {
        return new SurrenderState(heat, false, false, false, 0L);
    }

    // ------------------------------------------------------------------ T04

    @Test
    void aRefusedArrestLeavesEveryFieldExactlyWhereItWas() {
        SurrenderState before = new SurrenderState(80L, false, true, false, 12L);
        Decision decision = SurrenderService.decide(before, true, REDUCTION, JAILABLE);
        assertTrue(decision.proceed());
        assertEquals(50L, decision.sentencingHeat());

        Commit commit = SurrenderService.commit(before, decision, false, 900L);

        assertFalse(commit.applied());
        assertSame(before, commit.state(), "A refused arrest writes nothing, not even a copy");
        assertEquals(80L, commit.state().heat(), "The Heat discount is the payment; it is not made in advance");
        assertTrue(commit.state().escaped(), "Still resisting: nobody took them in");
        assertFalse(commit.state().surrenderCredited());
        assertEquals(12L, commit.state().lastSurrenderTick(), "No surrender happened, so none is stamped");
        assertEquals("mcacrime.surrender.failed", commit.messageKey());
    }

    @Test
    void anAcceptedArrestWritesTheDiscountAndTheCredit() {
        SurrenderState before = new SurrenderState(80L, false, true, false, 12L);
        Decision decision = SurrenderService.decide(before, true, REDUCTION, JAILABLE);

        Commit commit = SurrenderService.commit(before, decision, true, 900L);

        assertTrue(commit.applied());
        assertEquals(50L, commit.state().heat());
        assertFalse(commit.state().escaped());
        assertTrue(commit.state().surrenderCredited());
        assertEquals(900L, commit.state().lastSurrenderTick());
        assertEquals("mcacrime.surrender.done", commit.messageKey());
    }

    @Test
    void refusedAndNoCellAreNotAcceptances() {
        assertFalse(SurrenderService.accepted(ArrestService.Outcome.REFUSED));
        assertFalse(SurrenderService.accepted(ArrestService.Outcome.NO_CELL));
        assertTrue(SurrenderService.accepted(ArrestService.Outcome.ARRESTED));
        // Nothing to serve is still an arrest that happened: the player walks, the surrender stands.
        assertTrue(SurrenderService.accepted(ArrestService.Outcome.NO_SENTENCE));
    }

    // ------------------------------------------------------------------ T05

    @Test
    void aSecondSurrenderDuringASentenceIsRejectedAndDiscountsNothing() {
        SurrenderState serving = new SurrenderState(80L, true, false, true, 40L);

        Decision decision = SurrenderService.decide(serving, true, REDUCTION, JAILABLE);
        assertFalse(decision.proceed());
        assertEquals(80L, decision.sentencingHeat(), "The rejection must not carry a reduced figure");
        assertEquals("mcacrime.surrender.already_serving", decision.messageKey());

        Commit commit = SurrenderService.commit(serving, decision, true, 900L);
        assertFalse(commit.applied(), "Even a willing arrester cannot revive a rejected surrender");
        assertSame(serving, commit.state());
        assertEquals(40L, commit.state().lastSurrenderTick());
    }

    @Test
    void aSentenceThatWasNeverCreditedIsStillRejectedWhileItRuns() {
        // The flag records that the discount was given; the rejection does not depend on it. A prisoner
        // jailed by /crime jail has no credit and still may not surrender their way out.
        SurrenderState serving = new SurrenderState(80L, true, false, false, 0L);
        assertFalse(SurrenderService.decide(serving, true, REDUCTION, JAILABLE).proceed());
    }

    // ------------------------------------------------------------------ the surrounding rules

    @Test
    void nobodyToSurrenderToIsItsOwnRefusal() {
        Decision decision = SurrenderService.decide(free(80L), false, REDUCTION, JAILABLE);
        assertFalse(decision.proceed());
        assertEquals("mcacrime.surrender.noauthority", decision.messageKey());
    }

    @Test
    void surrenderAlwaysLandsBelowTheJailableThreshold() {
        // 400 Heat minus 30 is still jailable, and a surrender that leaves the player unfinable is a
        // surrender that changed nothing they can act on.
        Decision decision = SurrenderService.decide(free(400L), true, REDUCTION, JAILABLE);
        assertTrue(decision.proceed());
        assertEquals(JAILABLE - 1L, decision.sentencingHeat());
    }

    @Test
    void theDiscountNeverGoesNegative() {
        assertEquals(0L, SurrenderService.decide(free(5L), true, REDUCTION, JAILABLE).sentencingHeat());
    }
}
