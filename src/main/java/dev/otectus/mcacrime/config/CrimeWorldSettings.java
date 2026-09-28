package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.justice.ThiefCombatPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.util.List;
import java.util.Objects;

/**
 * One coherent, immutable read of the gameplay settings that may be owned by a world's game rules.
 * Every dimension resolves through the server's shared {@link GameRules} instance.
 */
public record CrimeWorldSettings(
        boolean useWorldRules,
        boolean crimeDetection,
        boolean observations,
        boolean thieves,
        boolean npcMugging,
        boolean seriousNpcCrime,
        boolean pvpCrime,
        boolean requireWitnessForHeat,
        boolean playerReports,
        ThiefCombatPolicy thiefCombatPolicy,
        boolean crimeNews,
        int newsIntervalDays,
        int newsMaxStories,
        int thiefMugCooldownTicks,
        int playerMugProtectionTicks,
        int maxMuggingsPerDay,
        int thiefJailTicks,
        boolean thiefProtectHotbar) {

    public static final int OVERRIDABLE_VALUE_COUNT = 17;

    public CrimeWorldSettings {
        Objects.requireNonNull(thiefCombatPolicy, "thiefCombatPolicy");
    }

    public static CrimeWorldSettings resolve(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return resolve(level.getServer());
    }

    public static CrimeWorldSettings resolve(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        GameRules rules = server.getGameRules();
        CrimeWorldSettings config = fromConfig();
        if (!rules.getBoolean(CrimeGameRules.USE_WORLD_RULES)) {
            return config;
        }
        return fromRules(rules, server);
    }

    /** Validated COMMON values, used while the selector is off and by {@code /crime rules import}. */
    public static CrimeWorldSettings fromConfig() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return new CrimeWorldSettings(false,
                c.enableCrimeDetection.get(), c.enableObservations.get(), c.enableThieves.get(),
                c.enableNpcMugging.get(), c.enableNpcCrime.get(), c.pvpCountsAsCrime.get(),
                c.requireWitnessForHeat.get(), c.enablePlayerReports.get(), c.thiefCombatPolicy.get(),
                c.enableCrimeNews.get(), c.crimeNewsIntervalDays.get(), c.crimeNewsMaxStories.get(),
                c.thiefMugCooldownTicks.get(), c.playerMugProtectionTicks.get(),
                c.maxMuggingsPerPlayerPerDay.get(), c.thiefJailTicks.get(), c.thiefProtectHotbar.get());
    }

    /** Fixed shipped defaults. They never depend on one server's config file. */
    public static CrimeWorldSettings defaults() {
        return new CrimeWorldSettings(true, true, true, true, true, false, false, true, true,
                ThiefCombatPolicy.ALL_THIEVES, true, 1, 4, 24_000, 36_000, 2, 12_000, true);
    }

    static CrimeWorldSettings fromRules(GameRules rules, MinecraftServer server) {
        return new CrimeWorldSettings(true,
                rules.getBoolean(CrimeGameRules.CRIME_DETECTION),
                rules.getBoolean(CrimeGameRules.OBSERVATIONS),
                rules.getBoolean(CrimeGameRules.THIEVES),
                rules.getBoolean(CrimeGameRules.NPC_MUGGING),
                rules.getBoolean(CrimeGameRules.SERIOUS_NPC_CRIME),
                rules.getBoolean(CrimeGameRules.PVP_CRIME),
                rules.getBoolean(CrimeGameRules.REQUIRE_WITNESS_FOR_HEAT),
                rules.getBoolean(CrimeGameRules.PLAYER_REPORTS),
                ThiefCombatPolicy.fromRule(CrimeGameRules.normalized(rules,
                        CrimeGameRules.THIEF_COMBAT_POLICY, 0, 2, server)),
                rules.getBoolean(CrimeGameRules.CRIME_NEWS),
                CrimeGameRules.normalized(rules, CrimeGameRules.NEWS_INTERVAL_DAYS, 1, 30, server),
                CrimeGameRules.normalized(rules, CrimeGameRules.NEWS_MAX_STORIES, 1, 8, server),
                CrimeGameRules.normalized(rules, CrimeGameRules.THIEF_MUG_COOLDOWN_TICKS,
                        0, 240_000, server),
                CrimeGameRules.normalized(rules, CrimeGameRules.PLAYER_MUG_PROTECTION_TICKS,
                        0, 1_728_000, server),
                CrimeGameRules.normalized(rules, CrimeGameRules.MAX_MUGGINGS_PER_DAY, 0, 64, server),
                CrimeGameRules.normalized(rules, CrimeGameRules.THIEF_JAIL_TICKS, 200, 240_000, server),
                rules.getBoolean(CrimeGameRules.THIEF_PROTECT_HOTBAR));
    }

    /** Pure precedence seam used by tests and transition code. */
    static CrimeWorldSettings select(boolean useWorldRules, CrimeWorldSettings config,
                                     CrimeWorldSettings world) {
        return useWorldRules ? world : config;
    }

    /** Writes every listed value before switching the selector, so a command publishes one final policy. */
    public static void apply(MinecraftServer server, CrimeWorldSettings settings) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(settings, "settings");
        GameRules rules = server.getGameRules();
        rules.getRule(CrimeGameRules.CRIME_DETECTION).set(settings.crimeDetection(), server);
        rules.getRule(CrimeGameRules.OBSERVATIONS).set(settings.observations(), server);
        rules.getRule(CrimeGameRules.THIEVES).set(settings.thieves(), server);
        rules.getRule(CrimeGameRules.NPC_MUGGING).set(settings.npcMugging(), server);
        rules.getRule(CrimeGameRules.SERIOUS_NPC_CRIME).set(settings.seriousNpcCrime(), server);
        rules.getRule(CrimeGameRules.PVP_CRIME).set(settings.pvpCrime(), server);
        rules.getRule(CrimeGameRules.REQUIRE_WITNESS_FOR_HEAT).set(settings.requireWitnessForHeat(), server);
        rules.getRule(CrimeGameRules.PLAYER_REPORTS).set(settings.playerReports(), server);
        rules.getRule(CrimeGameRules.THIEF_COMBAT_POLICY).set(settings.thiefCombatPolicy().ruleValue(), server);
        rules.getRule(CrimeGameRules.CRIME_NEWS).set(settings.crimeNews(), server);
        rules.getRule(CrimeGameRules.NEWS_INTERVAL_DAYS).set(settings.newsIntervalDays(), server);
        rules.getRule(CrimeGameRules.NEWS_MAX_STORIES).set(settings.newsMaxStories(), server);
        rules.getRule(CrimeGameRules.THIEF_MUG_COOLDOWN_TICKS).set(settings.thiefMugCooldownTicks(), server);
        rules.getRule(CrimeGameRules.PLAYER_MUG_PROTECTION_TICKS).set(settings.playerMugProtectionTicks(), server);
        rules.getRule(CrimeGameRules.MAX_MUGGINGS_PER_DAY).set(settings.maxMuggingsPerDay(), server);
        rules.getRule(CrimeGameRules.THIEF_JAIL_TICKS).set(settings.thiefJailTicks(), server);
        rules.getRule(CrimeGameRules.THIEF_PROTECT_HOTBAR).set(settings.thiefProtectHotbar(), server);
        rules.getRule(CrimeGameRules.USE_WORLD_RULES).set(true, server);
    }

    public String sourceName() {
        return useWorldRules ? "world rules" : "COMMON config";
    }

    /** Stable public names and effective values for operator diagnostics. */
    public List<Setting> settings() {
        return List.of(
                new Setting("mcaCrimeDetection", Boolean.toString(crimeDetection)),
                new Setting("mcaCrimeObservations", Boolean.toString(observations)),
                new Setting("mcaCrimeThieves", Boolean.toString(thieves)),
                new Setting("mcaCrimeNpcMugging", Boolean.toString(npcMugging)),
                new Setting("mcaCrimeSeriousNpcCrime", Boolean.toString(seriousNpcCrime)),
                new Setting("mcaCrimePvpCrime", Boolean.toString(pvpCrime)),
                new Setting("mcaCrimeRequireWitnessForHeat", Boolean.toString(requireWitnessForHeat)),
                new Setting("mcaCrimePlayerReports", Boolean.toString(playerReports)),
                new Setting("mcaCrimeThiefCombatPolicy", Integer.toString(thiefCombatPolicy.ruleValue())),
                new Setting("mcaCrimeNews", Boolean.toString(crimeNews)),
                new Setting("mcaCrimeNewsIntervalDays", Integer.toString(newsIntervalDays)),
                new Setting("mcaCrimeNewsMaxStories", Integer.toString(newsMaxStories)),
                new Setting("mcaCrimeThiefMugCooldownTicks", Integer.toString(thiefMugCooldownTicks)),
                new Setting("mcaCrimePlayerMugProtectionTicks", Integer.toString(playerMugProtectionTicks)),
                new Setting("mcaCrimeMaxMuggingsPerDay", Integer.toString(maxMuggingsPerDay)),
                new Setting("mcaCrimeThiefJailTicks", Integer.toString(thiefJailTicks)),
                new Setting("mcaCrimeThiefProtectHotbar", Boolean.toString(thiefProtectHotbar)));
    }

    public record Setting(String name, String value) {
    }
}
