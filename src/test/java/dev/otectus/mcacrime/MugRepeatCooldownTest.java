package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.MugProtectionRules;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-pair cooldown: the same thief may not come back for the same player (0.7.0, plan §4.3).
 *
 * <p>Being robbed twice by a face you recognise is the part of the complaint that read as harassment
 * rather than as a village having a thief in it, so the pair cooldown is longer than the shared one and
 * is kept per thief. The memory is bounded at eight, which is the other property worth pinning: a map
 * on a player capability that grew with the world would be a save file that never stops growing.
 */
class MugRepeatCooldownTest {

    private static final UUID THIEF = UUID.nameUUIDFromBytes("thief".getBytes());
    private static final UUID OTHER = UUID.nameUUIDFromBytes("other".getBytes());

    @Test
    void theThiefThatJustRobbedYouIsBarredWhileAnotherIsNot() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.recordMugger(THIEF, 1_000L, 0L);
        assertEquals(1_000L, data.pairCooldownUntil(THIEF));
        assertEquals(0L, data.pairCooldownUntil(OTHER));
        assertFalse(MugProtectionRules.eligible(0L, data.pairCooldownUntil(THIEF), 0, 0, 999L));
        assertTrue(MugProtectionRules.eligible(0L, data.pairCooldownUntil(OTHER), 0, 0, 999L));
        assertTrue(MugProtectionRules.eligible(0L, data.pairCooldownUntil(THIEF), 0, 0, 1_000L));
    }

    @Test
    void aSecondRecordForTheSameThiefExtendsRatherThanShortens() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.recordMugger(THIEF, 2_000L, 0L);
        data.recordMugger(THIEF, 500L, 0L);
        assertEquals(2_000L, data.pairCooldownUntil(THIEF));
    }

    @Test
    void expiredEntriesArePrunedRatherThanKeptForever() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.recordMugger(THIEF, 100L, 0L);
        data.recordMugger(OTHER, 5_000L, 0L);
        data.pruneRecentMuggers(1_000L);
        assertEquals(0L, data.pairCooldownUntil(THIEF));
        assertEquals(5_000L, data.pairCooldownUntil(OTHER));
    }

    @Test
    void theMemoryIsBoundedAndTheOldestEntryIsTheOneEvicted() {
        PlayerCrimeData data = new PlayerCrimeData();
        UUID first = UUID.nameUUIDFromBytes("thief-0".getBytes());
        data.recordMugger(first, 10_000L, 0L);
        for (int i = 1; i <= PlayerCrimeData.MAX_RECENT_MUGGERS; i++) {
            data.recordMugger(UUID.nameUUIDFromBytes(("thief-" + i).getBytes()), 10_000L, 0L);
        }
        assertEquals(PlayerCrimeData.MAX_RECENT_MUGGERS, data.recentMuggers().size());
        assertEquals(0L, data.pairCooldownUntil(first), "the oldest thief is the one forgotten");
        assertEquals(10_000L, data.pairCooldownUntil(
                UUID.nameUUIDFromBytes(("thief-" + PlayerCrimeData.MAX_RECENT_MUGGERS).getBytes())));
    }
}
