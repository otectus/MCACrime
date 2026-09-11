package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.MugProtectionRules;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily cap on how often one player may be robbed (0.7.0, plan §4.3/§4.4).
 *
 * <p>Counted per in-game day and rolled over on the day boundary, not cleared on a timer: a player who
 * logs in the next morning starts the day with a clean count, and one who plays through midnight does
 * not carry yesterday's total into today.
 */
class MugDailyCapTest {

    @Test
    void theCountRollsOverWhenTheDayDoesRatherThanAccumulating() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.recordMuggingOn(3L);
        data.recordMuggingOn(3L);
        assertEquals(2, data.muggingsOn(3L));
        assertEquals(0, data.muggingsOn(4L), "a new day starts at nothing");
        assertEquals(4L, data.getMuggingDay());
    }

    @Test
    void aPlayerAtTheCapIsRefusedAndOneBelowItIsNot() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.recordMuggingOn(0L);
        assertTrue(MugProtectionRules.eligible(0L, 0L, data.muggingsOn(0L), 2, 100L));
        data.recordMuggingOn(0L);
        assertFalse(MugProtectionRules.eligible(0L, 0L, data.muggingsOn(0L), 2, 100L));
        // Tomorrow the same player is available again.
        assertTrue(MugProtectionRules.eligible(0L, 0L, data.muggingsOn(1L), 2, 24_100L));
    }

    @Test
    void theShippedDefaultIsTwoMuggingsADayAndMuggingIsOn() {
        assertEquals(2, McaCrimeConfig.COMMON.maxMuggingsPerPlayerPerDay.getDefault());
        assertTrue(McaCrimeConfig.COMMON.enableNpcMugging.getDefault());
        assertEquals(36000, McaCrimeConfig.COMMON.playerMugProtectionTicks.getDefault());
        assertEquals(72000, McaCrimeConfig.COMMON.thiefVictimRepeatCooldownTicks.getDefault());
    }
}
