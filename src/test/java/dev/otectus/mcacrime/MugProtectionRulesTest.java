package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.MugProtectionRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The victim-scoped mugging rules (0.7.0, plan §4.3/§4.4).
 *
 * <p>The bug these end was that every mugging cooldown belonged to the thief, so N thieves robbed one
 * player in turn and each was inside its own limit. The three properties worth pinning are therefore:
 * a grant never shortens an existing window, each gate refuses on its own, and the daily counter rolls
 * over on a day boundary rather than on a timer.
 */
class MugProtectionRulesTest {

    @Test
    void aGrantTakesTheMaximumAndNeverShortensAnExistingWindow() {
        assertEquals(1_000L, MugProtectionRules.grant(1_000L, 100L, 200, 1.0D),
                "a shorter grant must not cut a longer window short");
        assertEquals(1_200L, MugProtectionRules.grant(1_000L, 200L, 1_000, 1.0D));
        assertEquals(600L, MugProtectionRules.grant(0L, 0L, 600, 1.0D));
    }

    @Test
    void aZeroLengthGrantLeavesTheWindowExactlyAsItWas() {
        assertEquals(500L, MugProtectionRules.grant(500L, 100L, 0, 1.0D));
        assertEquals(0L, MugProtectionRules.grant(0L, 100L, 0, 1.0D));
    }

    @Test
    void theFrequencyMultiplierDividesEveryWindowBecauseItScalesMuggingsNotCooldowns() {
        assertEquals(1_000L, MugProtectionRules.scale(1_000, 1.0D));
        assertEquals(500L, MugProtectionRules.scale(1_000, 2.0D), "twice as often is half the window");
        assertEquals(2_000L, MugProtectionRules.scale(1_000, 0.5D));
        assertEquals(0L, MugProtectionRules.scale(0, 4.0D));
        // Out of range for the config spec, and still not an infinite cooldown.
        assertEquals(1_000L, MugProtectionRules.scale(1_000, 0.0D));
    }

    @Test
    void eachGateRefusesOnItsOwnAndAnUnprotectedPlayerPassesThemAll() {
        assertTrue(MugProtectionRules.eligible(0L, 0L, 0, 2, 100L));
        assertFalse(MugProtectionRules.eligible(101L, 0L, 0, 2, 100L), "inside the shared window");
        assertFalse(MugProtectionRules.eligible(0L, 101L, 0, 2, 100L), "inside the pair cooldown");
        assertFalse(MugProtectionRules.eligible(0L, 0L, 2, 2, 100L), "at the daily cap");
    }

    @Test
    void aWindowEndsOnTheTickItNamesRatherThanOneLater() {
        assertFalse(MugProtectionRules.eligible(100L, 0L, 0, 0, 99L));
        assertTrue(MugProtectionRules.eligible(100L, 0L, 0, 0, 100L));
    }

    @Test
    void aDailyCapOfZeroDisablesTheCapRatherThanBanningMuggingEntirely() {
        assertTrue(MugProtectionRules.eligible(0L, 0L, 9_999, 0, 100L));
        assertTrue(MugProtectionRules.eligible(0L, 0L, 1, 2, 100L));
    }

    @Test
    void theDayRollsOverOnTheWorldDayBoundaryAndSurvivesANegativeClock() {
        assertEquals(0L, MugProtectionRules.day(0L));
        assertEquals(0L, MugProtectionRules.day(23_999L));
        assertEquals(1L, MugProtectionRules.day(24_000L));
        assertEquals(-1L, MugProtectionRules.day(-1L), "floor division, so day -1 is not day 0");
    }
}
