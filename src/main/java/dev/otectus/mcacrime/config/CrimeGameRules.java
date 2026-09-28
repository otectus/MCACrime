package dev.otectus.mcacrime.config;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.otectus.mcacrime.mixin.GameRuleTypeAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Registration and range enforcement for MCA: Crime's world-owned settings. */
public final class CrimeGameRules {

    public static final GameRules.Key<GameRules.BooleanValue> USE_WORLD_RULES = bool("mcaCrimeUseWorldRules", false);
    public static final GameRules.Key<GameRules.BooleanValue> CRIME_DETECTION = bool("mcaCrimeDetection", true);
    public static final GameRules.Key<GameRules.BooleanValue> OBSERVATIONS = bool("mcaCrimeObservations", true);
    public static final GameRules.Key<GameRules.BooleanValue> THIEVES = bool("mcaCrimeThieves", true);
    public static final GameRules.Key<GameRules.BooleanValue> NPC_MUGGING = bool("mcaCrimeNpcMugging", true);
    public static final GameRules.Key<GameRules.BooleanValue> SERIOUS_NPC_CRIME = bool("mcaCrimeSeriousNpcCrime", false);
    public static final GameRules.Key<GameRules.BooleanValue> PVP_CRIME = bool("mcaCrimePvpCrime", false);
    public static final GameRules.Key<GameRules.BooleanValue> REQUIRE_WITNESS_FOR_HEAT =
            bool("mcaCrimeRequireWitnessForHeat", true);
    public static final GameRules.Key<GameRules.BooleanValue> PLAYER_REPORTS = bool("mcaCrimePlayerReports", true);
    public static final GameRules.Key<GameRules.IntegerValue> THIEF_COMBAT_POLICY =
            integer("mcaCrimeThiefCombatPolicy", 2, 0, 2);
    public static final GameRules.Key<GameRules.BooleanValue> CRIME_NEWS = bool("mcaCrimeNews", true);
    public static final GameRules.Key<GameRules.IntegerValue> NEWS_INTERVAL_DAYS =
            integer("mcaCrimeNewsIntervalDays", 1, 1, 30);
    public static final GameRules.Key<GameRules.IntegerValue> NEWS_MAX_STORIES =
            integer("mcaCrimeNewsMaxStories", 4, 1, 8);
    public static final GameRules.Key<GameRules.IntegerValue> THIEF_MUG_COOLDOWN_TICKS =
            integer("mcaCrimeThiefMugCooldownTicks", 24_000, 0, 240_000);
    public static final GameRules.Key<GameRules.IntegerValue> PLAYER_MUG_PROTECTION_TICKS =
            integer("mcaCrimePlayerMugProtectionTicks", 36_000, 0, 1_728_000);
    public static final GameRules.Key<GameRules.IntegerValue> MAX_MUGGINGS_PER_DAY =
            integer("mcaCrimeMaxMuggingsPerDay", 2, 0, 64);
    public static final GameRules.Key<GameRules.IntegerValue> THIEF_JAIL_TICKS =
            integer("mcaCrimeThiefJailTicks", 12_000, 200, 240_000);
    public static final GameRules.Key<GameRules.BooleanValue> THIEF_PROTECT_HOTBAR =
            bool("mcaCrimeThiefProtectHotbar", true);

    private static final Set<GameRules.IntegerValue> NORMALIZING =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private CrimeGameRules() {
    }

    /** Forces class initialization from the mod constructor, before the world-creation UI is built. */
    public static void register() {
        // Static field initialization performs vanilla's global registration exactly once.
    }

    private static GameRules.Key<GameRules.BooleanValue> bool(String name, boolean defaultValue) {
        return GameRules.register(name, GameRules.Category.MISC, GameRules.BooleanValue.create(defaultValue));
    }

    private static GameRules.Key<GameRules.IntegerValue> integer(String name, int defaultValue, int min, int max) {
        GameRules.Type<GameRules.IntegerValue> type = GameRules.IntegerValue.create(defaultValue,
                (server, value) -> normalize(value, server, min, max));
        // Vanilla 1.20.1 exposes only an unbounded integer argument. Keep its value implementation and
        // visitor contract, changing only the argument supplier used by /gamerule.
        if ((Object) type instanceof GameRuleTypeAccessor accessor) {
            accessor.mcacrime$setArgument(() -> IntegerArgumentType.integer(min, max));
        }
        return GameRules.register(name, GameRules.Category.MISC, type);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void normalize(GameRules.IntegerValue value, MinecraftServer server, int min, int max) {
        int legal = clamp(value.get(), min, max);
        if (legal == value.get() || !NORMALIZING.add(value)) {
            return;
        }
        try {
            value.set(legal, server);
        } finally {
            NORMALIZING.remove(value);
        }
    }

    /** Normalizes values loaded from NBT or a creation-screen API before any gameplay reads them. */
    static int normalized(GameRules rules, GameRules.Key<GameRules.IntegerValue> key,
                          int min, int max, MinecraftServer server) {
        GameRules.IntegerValue rule = rules.getRule(key);
        int legal = clamp(rule.get(), min, max);
        if (legal != rule.get()) {
            rule.set(legal, server);
        }
        return legal;
    }
}
