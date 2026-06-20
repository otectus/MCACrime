package dev.otectus.mcacrime;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Forge common + client configuration (spec §12). "Everything is config" (spec §0 rule 5): every
 * number, chance, threshold, duration, and toggle is a default here, not a constant.
 *
 * <p>The full §12 key set is declared up front so the generated TOML is complete from day one and
 * server owners can see the whole design surface. Many keys are consumed only by later phases (crime
 * detection, jail, kidnapping, NPC crime); those are present-but-inert in 0.1.0. The keys the Karma/Heat
 * engine, band derivation, name coloring, and {@code /crime validate} actually read today are exercised.
 */
public final class McaCrimeConfig {

    public static final Common COMMON;
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Client CLIENT;
    public static final ForgeConfigSpec CLIENT_SPEC;

    static {
        final Pair<Common, ForgeConfigSpec> common = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = common.getLeft();
        COMMON_SPEC = common.getRight();

        final Pair<Client, ForgeConfigSpec> client = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
    }

    private McaCrimeConfig() {
    }

    public static final class Common {
        // bands (§1.1) — read by the engine + validator
        public final ForgeConfigSpec.IntValue karmaBlueThreshold;
        public final ForgeConfigSpec.IntValue karmaRedThreshold;
        public final ForgeConfigSpec.IntValue wantedHeatThreshold;

        // karma (§3) — read by the engine
        public final ForgeConfigSpec.IntValue karmaMin;
        public final ForgeConfigSpec.IntValue karmaMax;
        public final ForgeConfigSpec.IntValue karmaDecayPerDay;
        public final ForgeConfigSpec.DoubleValue unwitnessedKarmaFactor;

        // karma weights (§3.2) — skeleton, consumed by Phase 2 detection
        public final ForgeConfigSpec.IntValue tradeKarma;
        public final ForgeConfigSpec.IntValue giftKarma;
        public final ForgeConfigSpec.IntValue questCompleteKarma;
        public final ForgeConfigSpec.IntValue defendVillageKarma;
        public final ForgeConfigSpec.IntValue protectVillagerKarma;
        public final ForgeConfigSpec.IntValue harmVillagerKarma;
        public final ForgeConfigSpec.IntValue killVillagerKarma;
        public final ForgeConfigSpec.IntValue theftKarma;
        public final ForgeConfigSpec.IntValue vandalismKarma;
        public final ForgeConfigSpec.IntValue trespassKarma;
        public final ForgeConfigSpec.IntValue failQuestKarma;

        // heat (§3) — read by the engine
        public final ForgeConfigSpec.IntValue heatMax;
        public final ForgeConfigSpec.IntValue heatDecayPerMinute;
        public final ForgeConfigSpec.BooleanValue requireWitnessForHeat;

        // anti-farm caps (§3.3) — skeleton
        public final ForgeConfigSpec.IntValue perVillagerDailyKarmaCap;
        public final ForgeConfigSpec.IntValue perVillageDailyKarmaCap;
        public final ForgeConfigSpec.IntValue perPlayerDailyKarmaCap;
        public final ForgeConfigSpec.DoubleValue diminishingReturnsFactor;

        // enforcement (§4, §5.2) — skeleton
        public final ForgeConfigSpec.BooleanValue pvpCountsAsCrime;
        public final ForgeConfigSpec.BooleanValue raidGrace;
        public final ForgeConfigSpec.BooleanValue redIsLegalTarget;
        public final ForgeConfigSpec.BooleanValue allowKillingRed;
        public final ForgeConfigSpec.BooleanValue globalCrimePropagation;

        // kidnapping (§8) — skeleton
        public final ForgeConfigSpec.BooleanValue enableKidnappingNpc;
        public final ForgeConfigSpec.BooleanValue enableKidnappingPlayer;
        public final ForgeConfigSpec.IntValue captureChannelTicks;

        // NPC crime (§9) — skeleton
        public final ForgeConfigSpec.BooleanValue enableNpcCrime;
        public final ForgeConfigSpec.IntValue maxActiveNpcCrimesPerVillage;
        public final ForgeConfigSpec.IntValue minTimeBetweenNpcCrimes;

        // jail (§6, §7) — skeleton
        public final ForgeConfigSpec.BooleanValue enableFines;
        public final ForgeConfigSpec.BooleanValue enableBail;
        public final ForgeConfigSpec.IntValue maxCaptivityRealMinutes;
        public final ForgeConfigSpec.EnumValue<JailContainmentMode> jailContainmentMode;

        // protected / responder entities (§9, §15) — validated by /crime validate
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> protectedEntities;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> responderEntities;

        // ransom (§8.5) — skeleton
        public final ForgeConfigSpec.IntValue ransomCooldownPerVictimTicks;
        public final ForgeConfigSpec.IntValue ransomCooldownPerVillageTicks;

        // matching (§12) — skeleton (used when profession gating lands)
        public final ForgeConfigSpec.EnumValue<ProfessionMatchingMode> professionMatchingMode;

        // debug (§12.3)
        public final ForgeConfigSpec.BooleanValue strictJsonValidation;
        public final ForgeConfigSpec.BooleanValue debugLogging;

        Common(ForgeConfigSpec.Builder b) {
            b.push("bands");
            karmaBlueThreshold = b.comment("Karma at or above this is the Blue (lawful) band. Must be > redThreshold.")
                    .defineInRange("karmaBlueThreshold", 100, -1_000_000, 1_000_000);
            karmaRedThreshold = b.comment("Karma at or below this is the Red (outlaw) band. Must be < blueThreshold.")
                    .defineInRange("karmaRedThreshold", -100, -1_000_000, 1_000_000);
            wantedHeatThreshold = b.comment("Heat at or above this makes a player Wanted (actively pursued).")
                    .defineInRange("wantedHeatThreshold", 50, 0, 1_000_000);
            b.pop();

            b.push("karma");
            karmaMin = b.comment("Lower clamp on Karma.")
                    .defineInRange("karmaMin", -1_000_000, -1_000_000_000, 0);
            karmaMax = b.comment("Upper clamp on Karma.")
                    .defineInRange("karmaMax", 1_000_000, 0, 1_000_000_000);
            karmaDecayPerDay = b.comment("Karma normalised toward 0 by this much per online MC day (24000 ticks).")
                    .defineInRange("karmaDecayPerDay", 1, 0, 1_000_000);
            unwitnessedKarmaFactor = b.comment("Fraction of Karma penalty applied for unwitnessed crime (1.0 = full).")
                    .defineInRange("unwitnessedKarmaFactor", 1.0, 0.0, 1.0);
            b.push("weights");
            tradeKarma = b.defineInRange("tradeKarma", 1, -1000, 1000);
            giftKarma = b.defineInRange("giftKarma", 1, -1000, 1000);
            questCompleteKarma = b.defineInRange("questCompleteKarma", 5, -1000, 1000);
            defendVillageKarma = b.defineInRange("defendVillageKarma", 5, -1000, 1000);
            protectVillagerKarma = b.defineInRange("protectVillagerKarma", 10, -1000, 1000);
            harmVillagerKarma = b.defineInRange("harmVillagerKarma", -10, -1000, 1000);
            killVillagerKarma = b.defineInRange("killVillagerKarma", -50, -1000, 1000);
            theftKarma = b.defineInRange("theftKarma", -5, -1000, 1000);
            vandalismKarma = b.defineInRange("vandalismKarma", -5, -1000, 1000);
            trespassKarma = b.defineInRange("trespassKarma", -2, -1000, 1000);
            failQuestKarma = b.defineInRange("failQuestKarma", -2, -1000, 1000);
            b.pop();
            b.pop();

            b.push("heat");
            heatMax = b.comment("Upper clamp on Heat.")
                    .defineInRange("heatMax", 1_000_000, 0, 1_000_000_000);
            heatDecayPerMinute = b.comment("Heat bled off by this much per online minute (1200 ticks).")
                    .defineInRange("heatDecayPerMinute", 1, 0, 1_000_000);
            requireWitnessForHeat = b.comment("If true, only witnessed crimes generate Heat (spec §3.5).")
                    .define("requireWitnessForHeat", true);
            b.pop();

            b.push("antifarm");
            perVillagerDailyKarmaCap = b.defineInRange("perVillagerDailyKarmaCap", 20, 0, 1_000_000);
            perVillageDailyKarmaCap = b.defineInRange("perVillageDailyKarmaCap", 50, 0, 1_000_000);
            perPlayerDailyKarmaCap = b.defineInRange("perPlayerDailyKarmaCap", 100, 0, 1_000_000);
            diminishingReturnsFactor = b.defineInRange("diminishingReturnsFactor", 0.5, 0.0, 1.0);
            b.pop();

            b.push("enforcement");
            pvpCountsAsCrime = b.define("pvpCountsAsCrime", false);
            raidGrace = b.comment("Suppress crime detection during an active village raid.")
                    .define("raidGrace", true);
            redIsLegalTarget = b.define("redIsLegalTarget", false);
            allowKillingRed = b.define("allowKillingRed", false);
            globalCrimePropagation = b.comment("If true, a crime in one village sours every village.")
                    .define("globalCrimePropagation", false);
            b.pop();

            b.push("kidnapping");
            enableKidnappingNpc = b.define("enableKidnappingNpc", true);
            enableKidnappingPlayer = b.define("enableKidnappingPlayer", true);
            captureChannelTicks = b.comment("Channel/cast duration to capture, broken by hit/move/line-of-sight loss.")
                    .defineInRange("captureChannelTicks", 60, 0, 6000);
            b.pop();

            b.push("npccrime");
            enableNpcCrime = b.comment("Master switch for serious NPC crime (petty stays low even when false).")
                    .define("enableNpcCrime", false);
            maxActiveNpcCrimesPerVillage = b.defineInRange("maxActiveNpcCrimesPerVillage", 2, 0, 1000);
            minTimeBetweenNpcCrimes = b.defineInRange("minTimeBetweenNpcCrimes", 6000, 0, 1_000_000);
            b.pop();

            b.push("jail");
            enableFines = b.define("enableFines", true);
            enableBail = b.define("enableBail", false);
            maxCaptivityRealMinutes = b.comment("Hard real-online-time ceiling on how long any player can be held.")
                    .defineInRange("maxCaptivityRealMinutes", 360, 1, 100_000);
            jailContainmentMode = b.comment("How jail blocks resist escape: CONTAINMENT, PHYSICAL, or REINFORCED.")
                    .defineEnum("jailContainmentMode", JailContainmentMode.CONTAINMENT);
            b.pop();

            b.push("entities");
            protectedEntities = b.comment("Extra entity IDs (or #tags) treated as protected victims, beyond MCA villagers.")
                    .defineList("protectedEntities", List.of(), o -> o instanceof String);
            responderEntities = b.comment("Extra entity IDs (or #tags) treated as law responders, beyond MCA guards.")
                    .defineList("responderEntities", List.of(), o -> o instanceof String);
            b.pop();

            b.push("ransom");
            ransomCooldownPerVictimTicks = b.defineInRange("ransomCooldownPerVictimTicks", 24000, 0, 10_000_000);
            ransomCooldownPerVillageTicks = b.defineInRange("ransomCooldownPerVillageTicks", 12000, 0, 10_000_000);
            b.pop();

            b.push("matching");
            professionMatchingMode = b.comment("How professions are matched: STRICT, NORMALIZED, or LOOSE.")
                    .defineEnum("professionMatchingMode", ProfessionMatchingMode.NORMALIZED);
            b.pop();

            b.push("debug");
            strictJsonValidation = b.comment("Treat any malformed/unknown crime JSON as a hard error (later phases).")
                    .define("strictJsonValidation", false);
            debugLogging = b.define("debugLogging", false);
            b.pop();
        }
    }

    public static final class Client {
        public final ForgeConfigSpec.BooleanValue nameColorEnabled;
        public final ForgeConfigSpec.EnumValue<NameColorMode> nameColorMode;
        public final ForgeConfigSpec.BooleanValue showPlayerCardButton;
        public final ForgeConfigSpec.BooleanValue playerCardOpenByDefault;
        public final ForgeConfigSpec.BooleanValue chatFormatToggle;
        public final ForgeConfigSpec.BooleanValue captiveScreenToggle;

        Client(ForgeConfigSpec.Builder b) {
            b.push("client");
            nameColorEnabled = b.comment("Color player names by band (Blue/Grey/Red) on their nameplates.")
                    .define("nameColorEnabled", true);
            nameColorMode = b.comment("FULL recolors the whole name (only where unstyled); PREFIX_ONLY adds a colored marker and leaves the name untouched.")
                    .defineEnum("nameColorMode", NameColorMode.FULL);
            showPlayerCardButton = b.comment("Show the reputation player-card button in the inventory screen.")
                    .define("showPlayerCardButton", true);
            playerCardOpenByDefault = b.comment("Open the player card automatically whenever the inventory opens.")
                    .define("playerCardOpenByDefault", false);
            chatFormatToggle = b.comment("Reserved: color player names in chat by band (deferred — server-side, not yet wired).")
                    .define("chatFormatToggle", false);
            captiveScreenToggle = b.comment("Reserved: show the captive screen when held (later phase).")
                    .define("captiveScreenToggle", true);
            b.pop();
        }
    }

    /** How jail blocks resist a prisoner mining out (spec §7.3). */
    public enum JailContainmentMode {
        CONTAINMENT,
        PHYSICAL,
        REINFORCED
    }

    /** Profession matching strategy (spec §12). */
    public enum ProfessionMatchingMode {
        STRICT,
        NORMALIZED,
        LOOSE
    }

    /** How band name coloring is applied (spec §10.3). */
    public enum NameColorMode {
        FULL,
        PREFIX_ONLY
    }
}
