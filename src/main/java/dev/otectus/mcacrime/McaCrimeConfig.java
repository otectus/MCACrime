package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailContainmentMode;
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

        // positive-reward karma weights (§3.2) — skeleton for the reward/quest phases. Crime PENALTIES
        // are now data-driven in data/mcacrime/mcacrime/crimes/*.json (the single source of truth), so the
        // harm/kill/theft/vandalism/trespass weights moved there.
        public final ForgeConfigSpec.IntValue tradeKarma;
        public final ForgeConfigSpec.IntValue giftKarma;
        public final ForgeConfigSpec.IntValue questCompleteKarma;
        public final ForgeConfigSpec.IntValue defendVillageKarma;
        public final ForgeConfigSpec.IntValue protectVillagerKarma;
        public final ForgeConfigSpec.IntValue failQuestKarma;

        // heat (§3) — read by the engine
        public final ForgeConfigSpec.IntValue heatMax;
        public final ForgeConfigSpec.IntValue heatDecayPerMinute;
        public final ForgeConfigSpec.BooleanValue requireWitnessForHeat;

        // detection (§5) — read by the crime detector
        public final ForgeConfigSpec.BooleanValue enableCrimeDetection;
        public final ForgeConfigSpec.IntValue witnessRadius;
        public final ForgeConfigSpec.IntValue harmCooldownTicks;

        // anti-farm caps (§3.3) — skeleton
        public final ForgeConfigSpec.IntValue perVillagerDailyKarmaCap;
        public final ForgeConfigSpec.IntValue perVillageDailyKarmaCap;
        public final ForgeConfigSpec.IntValue perPlayerDailyKarmaCap;
        public final ForgeConfigSpec.DoubleValue diminishingReturnsFactor;

        // enforcement (§4, §5.2)
        public final ForgeConfigSpec.BooleanValue pvpCountsAsCrime;
        public final ForgeConfigSpec.BooleanValue raidGrace;
        public final ForgeConfigSpec.BooleanValue redIsLegalTarget;
        public final ForgeConfigSpec.BooleanValue allowKillingRed;
        public final ForgeConfigSpec.BooleanValue globalCrimePropagation;
        public final ForgeConfigSpec.DoubleValue guardAggroRadius;
        public final ForgeConfigSpec.IntValue guardScanIntervalTicks;
        public final ForgeConfigSpec.BooleanValue enableVillagerFlee;
        public final ForgeConfigSpec.DoubleValue villagerFleeRadius;

        // kidnapping / capture (§8) — read by the capture + custody services
        public final ForgeConfigSpec.BooleanValue enableKidnappingNpc;
        public final ForgeConfigSpec.BooleanValue enableKidnappingPlayer;
        public final ForgeConfigSpec.IntValue captureChannelTicks;
        public final ForgeConfigSpec.DoubleValue captureMaxMoveBlocks;
        public final ForgeConfigSpec.DoubleValue captureMaxRangeBlocks;
        public final ForgeConfigSpec.BooleanValue captureRequireLineOfSight;
        public final ForgeConfigSpec.DoubleValue captureLowHealthFraction;
        public final ForgeConfigSpec.BooleanValue villagerCaptureRelaxedVulnerability;
        public final ForgeConfigSpec.DoubleValue captureChannelMultiplierRope;
        public final ForgeConfigSpec.DoubleValue captureChannelMultiplierCuffs;
        public final ForgeConfigSpec.DoubleValue captureChannelMultiplierLockedCuffs;
        public final ForgeConfigSpec.DoubleValue restraintEscapeChanceRope;
        public final ForgeConfigSpec.DoubleValue restraintEscapeChanceCuffs;
        public final ForgeConfigSpec.DoubleValue restraintEscapeChanceLockedCuffs;
        public final ForgeConfigSpec.DoubleValue captiveTetherBlocks;
        public final ForgeConfigSpec.BooleanValue captiveCanEscapeByDistance;
        public final ForgeConfigSpec.BooleanValue npcCaptiveVirtualizeWhenUnloaded;

        // NPC crime (§9) — skeleton
        public final ForgeConfigSpec.BooleanValue enableNpcCrime;
        public final ForgeConfigSpec.IntValue maxActiveNpcCrimesPerVillage;
        public final ForgeConfigSpec.IntValue minTimeBetweenNpcCrimes;

        // jail (§6, §7)
        public final ForgeConfigSpec.BooleanValue enableFines;
        public final ForgeConfigSpec.BooleanValue enableBail;
        public final ForgeConfigSpec.IntValue maxCaptivityRealMinutes;
        public final ForgeConfigSpec.EnumValue<JailContainmentMode> jailContainmentMode;
        public final ForgeConfigSpec.IntValue maxJailCommandTicks;
        public final ForgeConfigSpec.IntValue jailRadiusDefault;
        public final ForgeConfigSpec.BooleanValue jailFallbackEnabled;
        public final ForgeConfigSpec.ConfigValue<List<? extends Integer>> jailFallbackPos;
        public final ForgeConfigSpec.ConfigValue<String> jailFallbackDim;

        // fines + surrender (§6)
        public final ForgeConfigSpec.IntValue fineBase;
        public final ForgeConfigSpec.IntValue finePerHeat;
        public final ForgeConfigSpec.IntValue jailableHeatThreshold;
        public final ForgeConfigSpec.DoubleValue blueFineMultiplier;
        public final ForgeConfigSpec.BooleanValue redCanPayFine;
        public final ForgeConfigSpec.DoubleValue surrenderNearRadius;
        public final ForgeConfigSpec.IntValue surrenderHeatReduction;
        public final ForgeConfigSpec.IntValue surrenderSentenceReductionPct;

        // protected / responder entities (§9, §15) — validated by /crime validate
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> protectedEntities;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> responderEntities;

        // ransom (§8.5) — read by the ransom service
        public final ForgeConfigSpec.IntValue ransomCooldownPerVictimTicks;
        public final ForgeConfigSpec.IntValue ransomCooldownPerVillageTicks;
        public final ForgeConfigSpec.IntValue ransomCooldownPerFamilyTicks;
        public final ForgeConfigSpec.IntValue ransomBaseAmount;
        public final ForgeConfigSpec.DoubleValue ransomSpouseMultiplier;
        public final ForgeConfigSpec.DoubleValue ransomParentMultiplier;
        public final ForgeConfigSpec.DoubleValue ransomChildMultiplier;
        public final ForgeConfigSpec.DoubleValue ransomSiblingMultiplier;
        public final ForgeConfigSpec.DoubleValue ransomRelativeMultiplier;
        public final ForgeConfigSpec.DoubleValue ransomVillageMultiplier;
        public final ForgeConfigSpec.BooleanValue enableVillageRansomFallback;
        public final ForgeConfigSpec.BooleanValue enableCloseFriendTier;
        public final ForgeConfigSpec.IntValue ransomDemandTtlTicks;

        // mugging (§8.6) — read by the mugging service
        public final ForgeConfigSpec.BooleanValue enableMugging;
        public final ForgeConfigSpec.IntValue muggingBaseLoot;
        public final ForgeConfigSpec.BooleanValue enableProfessionDeathDrops;

        // relationship consequences (§10.1, §11.3) — read by RelationshipConsequences
        public final ForgeConfigSpec.IntValue directVictimHeartLoss;
        public final ForgeConfigSpec.IntValue familyHeartLoss;
        public final ForgeConfigSpec.IntValue witnessTrustLoss;
        public final ForgeConfigSpec.IntValue villageRepDrop;
        public final ForgeConfigSpec.IntValue rescueHeartGain;
        public final ForgeConfigSpec.IntValue familyHeartGain;
        public final ForgeConfigSpec.IntValue villageRepRise;
        public final ForgeConfigSpec.IntValue restitutionHeartGain;
        public final ForgeConfigSpec.DoubleValue restitutionFractionOfFine;

        // ambient messages + chat coloring (§10.3) — read by AmbientMessages / ChatNameColor
        public final ForgeConfigSpec.BooleanValue ambientMessagesEnabled;
        public final ForgeConfigSpec.IntValue ambientMessageThrottleTicks;
        public final ForgeConfigSpec.BooleanValue chatNameColorEnabled;
        public final ForgeConfigSpec.EnumValue<NameColorMode> chatNameColorMode;

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
            b.push("rewardWeights");
            tradeKarma = b.defineInRange("tradeKarma", 1, -1000, 1000);
            giftKarma = b.defineInRange("giftKarma", 1, -1000, 1000);
            questCompleteKarma = b.defineInRange("questCompleteKarma", 5, -1000, 1000);
            defendVillageKarma = b.defineInRange("defendVillageKarma", 5, -1000, 1000);
            protectVillagerKarma = b.defineInRange("protectVillagerKarma", 10, -1000, 1000);
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

            b.push("detection");
            enableCrimeDetection = b.comment("Master switch for crime detection (harm/kill of protected NPCs).")
                    .define("enableCrimeDetection", true);
            witnessRadius = b.comment("Block radius in which an MCA villager/guard with line of sight witnesses a crime.")
                    .defineInRange("witnessRadius", 12, 1, 64);
            harmCooldownTicks = b.comment(
                    "Minimum ticks between counted harm crimes against the same victim by the same player",
                    "(anti-spam so a melee flurry is one crime, not many; 0 = every hit counts).")
                    .defineInRange("harmCooldownTicks", 20, 0, 6000);
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
            guardAggroRadius = b.comment("How far (blocks) a guard is made to pursue a Legal-Target player.")
                    .defineInRange("guardAggroRadius", 16.0, 1.0, 128.0);
            guardScanIntervalTicks = b.comment("Server ticks between guard-pursuit scans (also the re-apply cadence).")
                    .defineInRange("guardScanIntervalTicks", 10, 1, 200);
            enableVillagerFlee = b.comment("Make villagers flee Red players.")
                    .define("enableVillagerFlee", true);
            villagerFleeRadius = b.comment("How close (blocks) a Red player must be for villagers to flee.")
                    .defineInRange("villagerFleeRadius", 10.0, 1.0, 64.0);
            b.pop();

            b.push("kidnapping");
            enableKidnappingNpc = b.define("enableKidnappingNpc", true);
            enableKidnappingPlayer = b.define("enableKidnappingPlayer", true);
            captureChannelTicks = b.comment("Channel/cast duration to capture, broken by hit/move/line-of-sight loss.")
                    .defineInRange("captureChannelTicks", 60, 0, 6000);
            captureMaxMoveBlocks = b.comment("The capture channel breaks if the captor moves more than this many blocks from where it started.")
                    .defineInRange("captureMaxMoveBlocks", 1.5, 0.0, 64.0);
            captureMaxRangeBlocks = b.comment("The capture channel breaks if the target moves beyond this many blocks of the captor.")
                    .defineInRange("captureMaxRangeBlocks", 4.0, 0.5, 64.0);
            captureRequireLineOfSight = b.comment("The capture channel requires (and breaks on losing) line of sight to the target.")
                    .define("captureRequireLineOfSight", true);
            captureLowHealthFraction = b.comment("A player target counts as 'low health' (a capture vulnerability) at or below this fraction of max health.")
                    .defineInRange("captureLowHealthFraction", 0.35, 0.0, 1.0);
            villagerCaptureRelaxedVulnerability = b.comment("If true, ordinary (non-guard) villagers can be captured without meeting a vulnerability condition.")
                    .define("villagerCaptureRelaxedVulnerability", true);
            captureChannelMultiplierRope = b.comment("Per-restraint channel-duration multipliers (rope is faster, locked cuffs slower).")
                    .defineInRange("captureChannelMultiplierRope", 0.6, 0.1, 10.0);
            captureChannelMultiplierCuffs = b.defineInRange("captureChannelMultiplierCuffs", 1.0, 0.1, 10.0);
            captureChannelMultiplierLockedCuffs = b.defineInRange("captureChannelMultiplierLockedCuffs", 1.5, 0.1, 10.0);
            restraintEscapeChanceRope = b.comment("Per-attempt chance a captive breaks free of each restraint.")
                    .defineInRange("restraintEscapeChanceRope", 0.25, 0.0, 1.0);
            restraintEscapeChanceCuffs = b.defineInRange("restraintEscapeChanceCuffs", 0.08, 0.0, 1.0);
            restraintEscapeChanceLockedCuffs = b.comment("Locked cuffs: 0 means escape needs a key/rescue (Phase 7), not a roll.")
                    .defineInRange("restraintEscapeChanceLockedCuffs", 0.0, 0.0, 1.0);
            captiveTetherBlocks = b.comment("How far (blocks) a captive may stray from the hold point before being tethered back or (if allowed) escaping.")
                    .defineInRange("captiveTetherBlocks", 6.0, 1.0, 128.0);
            captiveCanEscapeByDistance = b.comment("If true, a kidnapping captive who strays past the tether escapes (no crime); if false they are pulled back.")
                    .define("captiveCanEscapeByDistance", true);
            npcCaptiveVirtualizeWhenUnloaded = b.comment("If true, an NPC captive in an unloaded chunk is virtually contained instead of force-loading the chunk.")
                    .define("npcCaptiveVirtualizeWhenUnloaded", true);
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
            maxJailCommandTicks = b.comment("Upper clamp on a /crime jail sentence (online ticks; 72000 = 1 online hour).")
                    .defineInRange("maxJailCommandTicks", 72000, 1, 100_000_000);
            jailRadiusDefault = b.comment("Default jail-region radius for /crime assignjail and the fallback.")
                    .defineInRange("jailRadiusDefault", 8, 1, 64);
            jailFallbackEnabled = b.comment("If true, jail at jailFallbackPos when no anchor is assigned (instead of refusing).")
                    .define("jailFallbackEnabled", false);
            jailFallbackPos = b.comment("Fallback jail position [x, y, z], used only when jailFallbackEnabled.")
                    .defineList("jailFallbackPos", List.of(0, 64, 0), o -> o instanceof Integer);
            jailFallbackDim = b.comment("Dimension id for the fallback jail position.")
                    .define("jailFallbackDim", "minecraft:overworld");
            b.pop();

            b.push("fines");
            fineBase = b.comment("Flat emerald cost of a fine, before the per-Heat term.")
                    .defineInRange("fineBase", 8, 0, 1_000_000);
            finePerHeat = b.comment("Extra emeralds charged per point of Heat.")
                    .defineInRange("finePerHeat", 1, 0, 1_000_000);
            jailableHeatThreshold = b.comment("At/above this Heat a fine is refused — the offender must serve jail or surrender.")
                    .defineInRange("jailableHeatThreshold", 80, 1, 1_000_000);
            blueFineMultiplier = b.comment("Fine multiplier for Blue (lawful) offenders.")
                    .defineInRange("blueFineMultiplier", 0.5, 0.0, 10.0);
            redCanPayFine = b.comment("If false, Red (outlaw) players must /crime surrender before they can pay a fine.")
                    .define("redCanPayFine", false);
            b.pop();

            b.push("surrender");
            surrenderNearRadius = b.comment("How close (blocks) a guard / jail / Blue player must be to surrender.")
                    .defineInRange("surrenderNearRadius", 8.0, 1.0, 64.0);
            surrenderHeatReduction = b.comment("Heat removed on surrender (also forced below the jailable threshold).")
                    .defineInRange("surrenderHeatReduction", 30, 0, 1_000_000);
            surrenderSentenceReductionPct = b.comment("Percent of a remaining jail sentence waived on surrender.")
                    .defineInRange("surrenderSentenceReductionPct", 25, 0, 100);
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
            ransomCooldownPerFamilyTicks = b.defineInRange("ransomCooldownPerFamilyTicks", 24000, 0, 10_000_000);
            ransomBaseAmount = b.comment("Base emerald ransom before per-relationship multipliers.")
                    .defineInRange("ransomBaseAmount", 16, 0, 1_000_000);
            ransomSpouseMultiplier = b.comment("Per-payer-tier ransom multipliers (spouse pays most; see payer priority in spec §8.5).")
                    .defineInRange("ransomSpouseMultiplier", 2.0, 0.0, 100.0);
            ransomParentMultiplier = b.defineInRange("ransomParentMultiplier", 1.5, 0.0, 100.0);
            ransomChildMultiplier = b.defineInRange("ransomChildMultiplier", 1.5, 0.0, 100.0);
            ransomSiblingMultiplier = b.defineInRange("ransomSiblingMultiplier", 1.2, 0.0, 100.0);
            ransomRelativeMultiplier = b.defineInRange("ransomRelativeMultiplier", 1.0, 0.0, 100.0);
            ransomVillageMultiplier = b.comment("Multiplier for the village-authority fallback ransom (the lower-value downgrade).")
                    .defineInRange("ransomVillageMultiplier", 0.75, 0.0, 100.0);
            enableVillageRansomFallback = b.comment("If no family payer can be found, fall back to a lower-value village-authority ransom (spec §8.5).")
                    .define("enableVillageRansomFallback", true);
            enableCloseFriendTier = b.comment("MCA has no NPC-to-NPC friendship edge; this tier is off by default and degrades to the village fallback.")
                    .define("enableCloseFriendTier", false);
            ransomDemandTtlTicks = b.comment("How long an open ransom demand stands before it expires.")
                    .defineInRange("ransomDemandTtlTicks", 12000, 0, 10_000_000);
            b.pop();

            b.push("mugging");
            enableMugging = b.define("enableMugging", true);
            muggingBaseLoot = b.comment("Emeralds a villager 'pays' on a successful mugging.")
                    .defineInRange("muggingBaseLoot", 4, 0, 1_000_000);
            enableProfessionDeathDrops = b.comment("If true, a villager killed while resisting a mugging drops profession loot; default false favors robbery over murder (§8.6).")
                    .define("enableProfessionDeathDrops", false);
            b.pop();

            b.push("relationship");
            directVictimHeartLoss = b.comment("Hearts the victim loses toward an offender who harms/mugs/kills them (§10.1).")
                    .defineInRange("directVictimHeartLoss", 2, 0, 1000);
            familyHeartLoss = b.defineInRange("familyHeartLoss", 1, 0, 1000);
            witnessTrustLoss = b.defineInRange("witnessTrustLoss", 1, 0, 1000);
            villageRepDrop = b.defineInRange("villageRepDrop", 2, 0, 1000);
            rescueHeartGain = b.comment("Hearts the rescued villager (and family) gain toward a rescuer (§10.1).")
                    .defineInRange("rescueHeartGain", 3, 0, 1000);
            familyHeartGain = b.defineInRange("familyHeartGain", 2, 0, 1000);
            villageRepRise = b.defineInRange("villageRepRise", 2, 0, 1000);
            restitutionHeartGain = b.defineInRange("restitutionHeartGain", 2, 0, 1000);
            restitutionFractionOfFine = b.comment("Fraction of a paid fine conceptually returned to the victim as relationship recovery (§11.3).")
                    .defineInRange("restitutionFractionOfFine", 0.5, 0.0, 1.0);
            b.pop();

            b.push("messages");
            ambientMessagesEnabled = b.comment("Send the player on-screen messages on band change, witnessed crime, and guard pursuit (spec §10.3).")
                    .define("ambientMessagesEnabled", true);
            ambientMessageThrottleTicks = b.comment("Minimum ticks between repeated ambient messages of the same kind to one player.")
                    .defineInRange("ambientMessageThrottleTicks", 100, 0, 100_000);
            chatNameColorEnabled = b.comment("Color player names in chat by band, server-side (the authoritative switch; the client chatFormatToggle is a display hint).")
                    .define("chatNameColorEnabled", false);
            chatNameColorMode = b.comment("FULL recolors the chat name; PREFIX_ONLY adds a colored marker and leaves the name untouched.")
                    .defineEnum("chatNameColorMode", NameColorMode.FULL);
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
