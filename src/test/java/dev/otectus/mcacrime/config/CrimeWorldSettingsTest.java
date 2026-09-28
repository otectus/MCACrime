package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.justice.ThiefCombatPolicy;
import net.minecraft.world.level.GameRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrimeWorldSettingsTest {

    @Test
    void shippedDefaultsMatchThePublicRuleContract() {
        CrimeWorldSettings defaults = CrimeWorldSettings.defaults();
        assertTrue(defaults.useWorldRules());
        assertTrue(defaults.crimeDetection());
        assertTrue(defaults.observations());
        assertTrue(defaults.thieves());
        assertTrue(defaults.npcMugging());
        assertFalse(defaults.seriousNpcCrime());
        assertFalse(defaults.pvpCrime());
        assertTrue(defaults.requireWitnessForHeat());
        assertTrue(defaults.playerReports());
        assertSame(ThiefCombatPolicy.ALL_THIEVES, defaults.thiefCombatPolicy());
        assertTrue(defaults.crimeNews());
        assertEquals(1, defaults.newsIntervalDays());
        assertEquals(4, defaults.newsMaxStories());
        assertEquals(24_000, defaults.thiefMugCooldownTicks());
        assertEquals(36_000, defaults.playerMugProtectionTicks());
        assertEquals(2, defaults.maxMuggingsPerDay());
        assertEquals(12_000, defaults.thiefJailTicks());
        assertTrue(defaults.thiefProtectHotbar());
        assertEquals(CrimeWorldSettings.OVERRIDABLE_VALUE_COUNT, defaults.settings().size());
    }

    @Test
    void selectorOwnsTheWholeSnapshot() {
        CrimeWorldSettings world = CrimeWorldSettings.defaults();
        CrimeWorldSettings config = new CrimeWorldSettings(false, false, false, false, false,
                true, true, false, false, ThiefCombatPolicy.NORMAL_LAW, false,
                30, 8, 0, 0, 0, 200, false);
        assertSame(config, CrimeWorldSettings.select(false, config, world));
        assertSame(world, CrimeWorldSettings.select(true, config, world));
    }

    @Test
    void everyIntegerRangeClampsBothEnds() {
        assertEquals(0, CrimeGameRules.clamp(-1, 0, 2));
        assertEquals(2, CrimeGameRules.clamp(3, 0, 2));
        assertEquals(1, CrimeGameRules.clamp(0, 1, 30));
        assertEquals(30, CrimeGameRules.clamp(31, 1, 30));
        assertEquals(1_728_000, CrimeGameRules.clamp(Integer.MAX_VALUE, 0, 1_728_000));
    }

    @Test
    void registeredRulesHaveFixedDefaults() {
        CrimeGameRules.register();
        GameRules rules = new GameRules();
        assertFalse(rules.getBoolean(CrimeGameRules.USE_WORLD_RULES));
        assertTrue(rules.getBoolean(CrimeGameRules.CRIME_DETECTION));
        assertFalse(rules.getBoolean(CrimeGameRules.SERIOUS_NPC_CRIME));
        assertEquals(2, rules.getInt(CrimeGameRules.THIEF_COMBAT_POLICY));
        assertEquals(24_000, rules.getInt(CrimeGameRules.THIEF_MUG_COOLDOWN_TICKS));
        assertEquals(36_000, rules.getInt(CrimeGameRules.PLAYER_MUG_PROTECTION_TICKS));
        assertEquals(12_000, rules.getInt(CrimeGameRules.THIEF_JAIL_TICKS));
    }
}
