package dev.otectus.mcacrime;

import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.7.0 additions to the player capability round-trip, copy on death, and read as zero when absent
 * (plan §4.2).
 *
 * <p>The last of those is the one that removes the need for a migration: every field here is optional,
 * so a save written by 0.6.4 loads as "never protected, never robbed, never searched" rather than as a
 * corrupt tag — the same precedent {@code resistingArrestUntilTick} and {@code priorWarrants} set.
 */
class PlayerCrimeDataMugFieldsTest {

    private static final UUID THIEF = UUID.nameUUIDFromBytes("thief".getBytes());

    private static PlayerCrimeData populated() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.setMugProtectionUntilTick(4_242L);
        data.recordMugger(THIEF, 9_000L, 0L);
        data.recordMuggingOn(7L);
        data.setLastContrabandFingerprint(-1234567890123L);
        data.setLastContrabandChargeTick(555L);
        return data;
    }

    @Test
    void everyNewFieldSurvivesSaveAndLoad() {
        PlayerCrimeData loaded = new PlayerCrimeData();
        loaded.load(populated().save());
        assertEquals(4_242L, loaded.getMugProtectionUntilTick());
        assertEquals(9_000L, loaded.pairCooldownUntil(THIEF));
        assertEquals(1, loaded.getMuggingsToday());
        assertEquals(7L, loaded.getMuggingDay());
        assertEquals(-1234567890123L, loaded.getLastContrabandFingerprint());
        assertEquals(555L, loaded.getLastContrabandChargeTick());
    }

    @Test
    void everyNewFieldSurvivesDeath() {
        PlayerCrimeData respawned = new PlayerCrimeData();
        respawned.copyFrom(populated());
        assertEquals(4_242L, respawned.getMugProtectionUntilTick());
        assertEquals(9_000L, respawned.pairCooldownUntil(THIEF));
        assertEquals(1, respawned.getMuggingsToday());
        assertEquals(7L, respawned.getMuggingDay());
        assertEquals(-1234567890123L, respawned.getLastContrabandFingerprint());
        assertEquals(555L, respawned.getLastContrabandChargeTick());
    }

    @Test
    void aCopyDoesNotShareTheMuggerMapWithItsSource() {
        PlayerCrimeData source = populated();
        PlayerCrimeData copy = new PlayerCrimeData();
        copy.copyFrom(source);
        source.recentMuggers().clear();
        assertEquals(9_000L, copy.pairCooldownUntil(THIEF));
    }

    @Test
    void aPre070SaveLoadsAsZeroWithNoMigration() {
        CompoundTag legacy = new CompoundTag();
        legacy.putLong("karma", 25L);
        legacy.putLong("heat", 5L);
        PlayerCrimeData loaded = new PlayerCrimeData();
        loaded.load(legacy);
        assertEquals(0L, loaded.getMugProtectionUntilTick());
        assertEquals(0, loaded.getMuggingsToday());
        assertEquals(0L, loaded.getMuggingDay());
        assertEquals(0L, loaded.getLastContrabandFingerprint());
        assertEquals(0L, loaded.getLastContrabandChargeTick());
        assertTrue(loaded.recentMuggers().isEmpty());
        assertEquals(25L, loaded.getKarma(), "the fields that were there still load");
    }
}
