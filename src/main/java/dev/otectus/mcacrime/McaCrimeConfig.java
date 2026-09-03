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
        public final ForgeConfigSpec.IntValue maxStoredWitnesses;

        // observations and reports (§12) — the identity-carrying replacement for the witness count
        public final ForgeConfigSpec.BooleanValue enableObservations;
        public final ForgeConfigSpec.IntValue hearingWitnessRadius;
        public final ForgeConfigSpec.IntValue reportRadius;
        public final ForgeConfigSpec.IntValue observationStatuteTicks;
        public final ForgeConfigSpec.DoubleValue reportConfidenceThreshold;

        // villager reaction state machine (§11.2)
        public final ForgeConfigSpec.BooleanValue enableVillagerReactions;
        public final ForgeConfigSpec.IntValue reactionTickIntervalTicks;
        public final ForgeConfigSpec.IntValue reactionNavigationIntervalTicks;
        public final ForgeConfigSpec.IntValue maxActiveReactions;
        public final ForgeConfigSpec.IntValue reactionThreatenedTicks;
        public final ForgeConfigSpec.IntValue reactionFleeTicks;
        public final ForgeConfigSpec.IntValue reactionSeekHelpTicks;
        public final ForgeConfigSpec.IntValue reactionHideTicks;
        public final ForgeConfigSpec.IntValue reactionRecoveryTicks;
        public final ForgeConfigSpec.IntValue safeDestinationSamples;

        // dialogue (§16)
        public final ForgeConfigSpec.BooleanValue enableDialogue;
        public final ForgeConfigSpec.IntValue dialogueCooldownTicks;

        // guard challenge (§13.2)
        public final ForgeConfigSpec.BooleanValue enableGuardChallenge;
        public final ForgeConfigSpec.IntValue guardChallengeWindowTicks;
        public final ForgeConfigSpec.DoubleValue guardChallengeRadius;
        public final ForgeConfigSpec.IntValue resistingArrestTicks;
        public final ForgeConfigSpec.BooleanValue manageGuardPopulation;
        public final ForgeConfigSpec.DoubleValue guardPopulationRatio;
        public final ForgeConfigSpec.IntValue guardPopulationMinimum;
        public final ForgeConfigSpec.IntValue guardPopulationMaxPerPass;
        public final ForgeConfigSpec.IntValue guardPopulationScanIntervalTicks;
        public final ForgeConfigSpec.IntValue guardPopulationCooldownTicks;

        // rescue (§14.3)
        public final ForgeConfigSpec.BooleanValue enableRescue;
        public final ForgeConfigSpec.IntValue rescueChannelTicks;

        // bail (§13.4) — the previously-inert enableBail switch, given a price
        public final ForgeConfigSpec.IntValue bailCostPerMinute;
        public final ForgeConfigSpec.DoubleValue bailMinServedFraction;

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
        public final ForgeConfigSpec.IntValue maxUnlawfulCaptivesPerCaptor;
        public final ForgeConfigSpec.IntValue captorDisconnectGraceTicks;
        public final ForgeConfigSpec.IntValue escapeWorkTicksRope;
        public final ForgeConfigSpec.IntValue escapeWorkTicksCuffs;
        public final ForgeConfigSpec.IntValue escapeAttemptCooldownTicks;

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
        public final ForgeConfigSpec.BooleanValue buildHoldingCell;
        public final ForgeConfigSpec.IntValue holdingCellSearchRadius;
        public final ForgeConfigSpec.IntValue holdingCellLifetimeTicks;
        public final ForgeConfigSpec.IntValue sentenceBaseTicks;
        public final ForgeConfigSpec.IntValue sentenceTicksPerHeat;
        public final ForgeConfigSpec.IntValue sentenceTicksPerCharge;
        public final ForgeConfigSpec.IntValue arrestEscortTimeoutTicks;
        public final ForgeConfigSpec.DoubleValue escortTetherBlocks;
        public final ForgeConfigSpec.DoubleValue escortLeashBlocks;
        public final ForgeConfigSpec.DoubleValue escortSpeedPenalty;
        public final ForgeConfigSpec.DoubleValue escortWalkSpeed;
        public final ForgeConfigSpec.IntValue escortNavigationIntervalTicks;
        public final ForgeConfigSpec.IntValue escortStuckScans;
        public final ForgeConfigSpec.BooleanValue restrainedPlayerRestrictions;
        public final ForgeConfigSpec.IntValue arrestRecoveryTicks;
        public final ForgeConfigSpec.DoubleValue jailAssignedMaxDistance;
        public final ForgeConfigSpec.BooleanValue jailFallbackEnabled;
        public final ForgeConfigSpec.ConfigValue<List<? extends Integer>> jailFallbackPos;
        public final ForgeConfigSpec.ConfigValue<String> jailFallbackDim;

        // fines + surrender (§6)
        public final ForgeConfigSpec.IntValue fineBase;
        public final ForgeConfigSpec.IntValue finePerHeat;
        public final ForgeConfigSpec.IntValue jailableHeatThreshold;
        public final ForgeConfigSpec.DoubleValue blueFineMultiplier;
        public final ForgeConfigSpec.BooleanValue redCanPayFine;
        public final ForgeConfigSpec.IntValue maxCasesPerFinePayment;
        public final ForgeConfigSpec.DoubleValue surrenderNearRadius;
        public final ForgeConfigSpec.IntValue surrenderHeatReduction;
        public final ForgeConfigSpec.IntValue surrenderSentenceReductionPct;

        // protected / responder entities (§9, §15) — validated by /crime validate
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> protectedEntities;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> responderEntities;

        // weapon-in-hand trigger + weapon classification (0.5.0) — read by item.weapon and the interact handler
        public final ForgeConfigSpec.BooleanValue weaponTriggerEnabled;
        public final ForgeConfigSpec.BooleanValue weaponTriggerRequireSneak;
        public final ForgeConfigSpec.BooleanValue weaponTriggerAllowOffHand;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> weaponWhitelist;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> weaponBlacklist;
        public final ForgeConfigSpec.BooleanValue weaponAutoDetect;
        public final ForgeConfigSpec.DoubleValue weaponAutoDetectMinAttackDamage;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> weaponGunKeywords;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> weaponMods;
        public final ForgeConfigSpec.BooleanValue mugRequiresWeapon;

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
        public final ForgeConfigSpec.IntValue villageTreasuryInitialBalance;

        // mugging (§8.6) — read by the mugging service
        public final ForgeConfigSpec.BooleanValue enableMugging;
        public final ForgeConfigSpec.IntValue muggingBaseLoot;
        public final ForgeConfigSpec.BooleanValue enableProfessionDeathDrops;
        public final ForgeConfigSpec.IntValue muggingChannelTicks;
        public final ForgeConfigSpec.IntValue muggingAttemptCooldownTicks;
        public final ForgeConfigSpec.IntValue muggingVictimRecoveryTicks;
        public final ForgeConfigSpec.IntValue muggingFearMemoryTicks;
        public final ForgeConfigSpec.IntValue muggingPanicTicks;
        public final ForgeConfigSpec.IntValue muggingActorSuccessCapPerDay;
        public final ForgeConfigSpec.IntValue muggingActorValueCapPerDay;
        public final ForgeConfigSpec.IntValue muggingVillageValueCapPerDay;
        public final ForgeConfigSpec.IntValue muggingPurseCapacity;
        public final ForgeConfigSpec.IntValue muggingPurseInitialMax;
        public final ForgeConfigSpec.IntValue muggingPurseDailyIncome;
        public final ForgeConfigSpec.BooleanValue allowHostileActionsAgainstChildren;
        public final ForgeConfigSpec.BooleanValue allowGameplayCommandFallback;

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

        // --- integrations (optional companion mods) ---
        public final ForgeConfigSpec.BooleanValue enableReputation;
        public final ForgeConfigSpec.BooleanValue mirrorReputationFallback;
        public final ForgeConfigSpec.BooleanValue suppressLocalVillagePenalty;
        public final ForgeConfigSpec.BooleanValue replayPendingOperations;
        public final ForgeConfigSpec.IntValue pumpIntervalTicks;
        public final ForgeConfigSpec.IntValue pumpBudgetPerTick;
        public final ForgeConfigSpec.IntValue maxDeliveryAttempts;
        public final ForgeConfigSpec.IntValue retryBaseDelayTicks;
        public final ForgeConfigSpec.IntValue retryMaxDelayTicks;
        public final ForgeConfigSpec.IntValue dedupeRetentionTicks;
        public final ForgeConfigSpec.ConfigValue<String> fineResolutionStatus;
        public final ForgeConfigSpec.ConfigValue<String> servedResolutionStatus;

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
            maxStoredWitnesses = b.comment(
                    "How many witness identities a single crime record keeps. The nearest ones are kept and",
                    "the true crowd size is still recorded, so a riot outside a busy village does not write an",
                    "unbounded list into the save file.")
                    .defineInRange("maxStoredWitnesses", 8, 1, 64);
            b.comment(
                    "Observations record who saw a crime, in what role, and how sure they are -- the thing a",
                    "bare witness count cannot express. Three radii, because seeing and hearing are not the",
                    "same act and neither is walking to a guard afterwards.")
                    .push("observations");
            enableObservations = b.comment(
                    "Record identity-carrying observations. Off falls back to the count-only witness scan,",
                    "which means no reports, no reaction triggers, and guards that only know what Heat says.")
                    .define("enableObservations", true);
            hearingWitnessRadius = b.comment(
                    "Block radius in which a struggle can be heard without being seen. Larger than the sight",
                    "radius on purpose, and halved through each solid block between the two.")
                    .defineInRange("hearingWitnessRadius", 16, 0, 64);
            reportRadius = b.comment("How far a witness will search for a guard or authority to report to.")
                    .defineInRange("reportRadius", 24, 1, 128);
            observationStatuteTicks = b.comment(
                    "How long an undelivered observation stays reportable. Past this it is marked expired",
                    "rather than deleted -- 'they saw it and never told anyone in time' is a different fact",
                    "from 'nobody saw it', and dialogue needs the difference.")
                    .defineInRange("observationStatuteTicks", 168_000, 1200, 10_000_000);
            reportConfidenceThreshold = b.comment(
                    "Report confidence at or above which an arrest is justified. Below it a guard",
                    "investigates instead, which is what a heard-but-unseen crime should produce.")
                    .defineInRange("reportConfidenceThreshold", 0.6, 0.0, 1.0);
            b.pop();
            b.pop();

            b.comment(
                    "Villagers with an active reaction are driven by a bounded server-side controller. Only",
                    "villagers actually reacting are ticked -- there is no per-tick scan of every villager in",
                    "the world, and a villager with no reaction is left entirely to MCA's own AI.")
                    .push("reactions");
            enableVillagerReactions = b.comment("Master switch for the reaction state machine.")
                    .define("enableVillagerReactions", true);
            reactionTickIntervalTicks = b.comment("Ticks between state evaluations for an active reaction.")
                    .defineInRange("reactionTickIntervalTicks", 5, 1, 100);
            reactionNavigationIntervalTicks = b.comment(
                    "Ticks between path reissues. Reissuing every tick fights MCA's own sensors and produces",
                    "visible jitter, so navigation is deliberately slower than state evaluation.")
                    .defineInRange("reactionNavigationIntervalTicks", 10, 1, 200);
            maxActiveReactions = b.comment(
                    "Hard ceiling on simultaneously reacting villagers. Past it, new reactions are refused",
                    "rather than queued: a village-wide panic must not become unbounded tick work.")
                    .defineInRange("maxActiveReactions", 64, 1, 512);
            reactionThreatenedTicks = b.defineInRange("reactionThreatenedTicks", 60, 0, 12000);
            reactionFleeTicks = b.defineInRange("reactionFleeTicks", 200, 0, 12000);
            reactionSeekHelpTicks = b.defineInRange("reactionSeekHelpTicks", 400, 0, 24000);
            reactionHideTicks = b.defineInRange("reactionHideTicks", 600, 0, 24000);
            reactionRecoveryTicks = b.comment(
                    "How long a villager keeps refusing or altering interaction with the offender after the"
                            + " reaction itself ends. Memory outlives this; only the behaviour stops.")
                    .defineInRange("reactionRecoveryTicks", 1200, 0, 72000);
            safeDestinationSamples = b.comment(
                    "How many candidate destinations a fleeing villager scores. Bounded sampling, never an"
                            + " unbounded POI search on the server thread.")
                    .defineInRange("safeDestinationSamples", 8, 1, 32);
            b.pop();

            b.comment("Data-driven villager lines. The server picks the line; the client renders the key.")
                    .push("dialogue");
            enableDialogue = b.define("enableDialogue", true);
            dialogueCooldownTicks = b.comment("Minimum ticks between spoken lines from one villager to one player.")
                    .defineInRange("dialogueCooldownTicks", 40, 0, 12000);
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
            enableGuardChallenge = b.comment(
                    "A guard with a report challenges before it attacks: it states the charge and opens a",
                    "window to surrender, pay, or refuse. Off returns guards to attacking a Wanted player",
                    "on sight, which is the behaviour this replaces.")
                    .define("enableGuardChallenge", true);
            guardChallengeWindowTicks = b.comment(
                    "How long a challenged player has to answer. No answer is a refusal, not a pardon.")
                    .defineInRange("guardChallengeWindowTicks", 200, 20, 12000);
            guardChallengeRadius = b.comment("How close a guard must be to issue or keep a challenge.")
                    .defineInRange("guardChallengeRadius", 6.0, 1.0, 32.0);
            resistingArrestTicks = b.comment(
                    "How long refusing a guard's challenge keeps you a lawful target, in online ticks.",
                    "This is what makes refusal a decision rather than a message: for the duration,",
                    "guards may use force whether or not your Heat would otherwise justify it.")
                    .defineInRange("resistingArrestTicks", 2400, 20, 1_728_000);

            b.push("guards");
            manageGuardPopulation = b.comment(
                    "Keep villages topped up to guardPopulationRatio by converting eligible adult",
                    "villagers to the guard profession.",
                    "",
                    "OFF BY DEFAULT, and read this before turning it on: MCA Reborn already does this. Its",
                    "own guardSpawnFraction defaults to 0.175, which is higher than the 0.10 default here,",
                    "so with both systems running MCA reaches its target first and this pass finds nothing",
                    "to do. Enable this only if you have turned the MCA fraction down, or want a floor that",
                    "MCA does not provide. Whichever target is higher wins; the two do not add up.",
                    "",
                    "This pass only ever adds guards. It never converts a guard back into a villager, so a",
                    "village whose population dips cannot lose the guards it already has.")
                    .define("manageGuardPopulation", false);
            guardPopulationRatio = b.comment(
                    "Fraction of a village's eligible villagers that should be guards. 0.10 = 10%.",
                    "The target is ceil(population x ratio), floored at guardPopulationMinimum, so 10",
                    "villagers ask for 1 guard, 20 ask for 2, and 50 ask for 5.")
                    .defineInRange("guardPopulationRatio", 0.10, 0.0, 1.0);
            guardPopulationMinimum = b.comment(
                    "Fewest guards a village with any population at all should have. An empty village is",
                    "still left alone -- the minimum is a floor, not a way to conjure a guard from nobody.")
                    .defineInRange("guardPopulationMinimum", 1, 0, 64);
            guardPopulationMaxPerPass = b.comment(
                    "How many villagers may be converted in a single pass. Kept at one so a village grows",
                    "its guard force gradually rather than a third of the population changing clothes at once.")
                    .defineInRange("guardPopulationMaxPerPass", 1, 1, 16);
            guardPopulationScanIntervalTicks = b.comment(
                    "Game ticks between population passes. One village in one dimension is examined per",
                    "pass, and only dimensions with players in them are considered at all.")
                    .defineInRange("guardPopulationScanIntervalTicks", 1200, 200, 72_000);
            guardPopulationCooldownTicks = b.comment(
                    "Game ticks before the same village is examined again. This is what stops a population",
                    "wobbling by one from causing repeated re-evaluation; it should be comfortably longer",
                    "than guardPopulationScanIntervalTicks.")
                    .defineInRange("guardPopulationCooldownTicks", 6000, 1200, 1_728_000);
            b.pop();

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
                    .define("villagerCaptureRelaxedVulnerability", false);
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
            maxUnlawfulCaptivesPerCaptor = b.comment("Maximum simultaneous unlawful captives owned by one captor.")
                    .defineInRange("maxUnlawfulCaptivesPerCaptor", 1, 1, 16);
            captorDisconnectGraceTicks = b.comment("Player captive release grace after their captor disconnects.")
                    .defineInRange("captorDisconnectGraceTicks", 1200, 0, 72000);
            escapeWorkTicksRope = b.comment("Continuous escape work required for rope.")
                    .defineInRange("escapeWorkTicksRope", 200, 1, 72000);
            escapeWorkTicksCuffs = b.comment("Continuous escape work required for ordinary cuffs.")
                    .defineInRange("escapeWorkTicksCuffs", 600, 1, 72000);
            enableRescue = b.comment(
                    "Let a third party free somebody else's captive. This is the counterplay to kidnapping:",
                    "with it off, only the captor or the captive can ever end a captivity.")
                    .define("enableRescue", true);
            rescueChannelTicks = b.comment("Channel duration to cut or unlock another player's captive free.")
                    .defineInRange("rescueChannelTicks", 40, 0, 6000);
            escapeAttemptCooldownTicks = b.comment("Cooldown stamped when escape work starts; repeated input does not reroll.")
                    .defineInRange("escapeAttemptCooldownTicks", 1200, 0, 72000);
            b.pop();

            b.push("npccrime");
            enableNpcCrime = b.comment("Master switch for serious NPC crime (petty stays low even when false).")
                    .define("enableNpcCrime", false);
            maxActiveNpcCrimesPerVillage = b.defineInRange("maxActiveNpcCrimesPerVillage", 2, 0, 1000);
            minTimeBetweenNpcCrimes = b.defineInRange("minTimeBetweenNpcCrimes", 6000, 0, 1_000_000);
            b.pop();

            b.push("jail");
            enableFines = b.define("enableFines", true);
            enableBail = b.comment(
                    "Let a jailed player buy out the rest of a sentence. Off is the historical default and",
                    "keeps a sentence something you serve rather than something you price.")
                    .define("enableBail", false);
            bailCostPerMinute = b.comment("Emeralds charged per remaining real minute of sentence.")
                    .defineInRange("bailCostPerMinute", 4, 0, 100_000);
            bailMinServedFraction = b.comment(
                    "Fraction of the sentence that must already be served before bail is offered. 0 lets a"
                            + " sentence be bought out the instant it starts.")
                    .defineInRange("bailMinServedFraction", 0.25, 0.0, 1.0);
            maxCaptivityRealMinutes = b.comment("Hard real-online-time ceiling on how long any player can be held.")
                    .defineInRange("maxCaptivityRealMinutes", 360, 1, 100_000);
            jailContainmentMode = b.comment("How jail blocks resist escape: CONTAINMENT, PHYSICAL, or REINFORCED.")
                    .defineEnum("jailContainmentMode", JailContainmentMode.CONTAINMENT);
            maxJailCommandTicks = b.comment("Upper clamp on a /crime jail sentence (online ticks; 72000 = 1 online hour).")
                    .defineInRange("maxJailCommandTicks", 72000, 1, 100_000_000);
            jailRadiusDefault = b.comment("Default jail-region radius for /crime assignjail and the fallback.")
                    .defineInRange("jailRadiusDefault", 8, 1, 64);
            buildHoldingCell = b.comment(
                    "When no jail anchor is assigned and no fallback is configured, build a temporary",
                    "iron-bar holding cell near the arrest and take it down again on release, restoring",
                    "every block it replaced. Off means an arrest with nowhere to put the prisoner is",
                    "refused instead, and surrender does nothing until an operator runs /crime assignjail.")
                    .define("buildHoldingCell", true);
            holdingCellSearchRadius = b.comment(
                    "How far (blocks) from the arrest to look for ground clear enough to build a cell on.")
                    .defineInRange("holdingCellSearchRadius", 24, 4, 96);
            holdingCellLifetimeTicks = b.comment(
                    "Hard ceiling on how long a built holding cell may stand, in game ticks (24000 = one",
                    "Minecraft day). This is the leak guard: a player who is arrested and never logs in",
                    "again would otherwise leave a cage in somebody's village for the life of the save.")
                    .defineInRange("holdingCellLifetimeTicks", 1_728_000, 1200, 100_000_000);
            sentenceBaseTicks = b.comment("Fixed part of a sentence, in online ticks (1200 = one online minute).")
                    .defineInRange("sentenceBaseTicks", 1200, 0, 100_000_000);
            sentenceTicksPerHeat = b.comment("Added sentence length per point of Heat at the time of arrest.")
                    .defineInRange("sentenceTicksPerHeat", 30, 0, 1_000_000);
            sentenceTicksPerCharge = b.comment("Added sentence length per outstanding charge being answered for.")
                    .defineInRange("sentenceTicksPerCharge", 200, 0, 1_000_000);
            arrestEscortTimeoutTicks = b.comment(
                    "How long a guard is given to walk an arrested player to the cell before the arrest",
                    "completes by teleport instead. An escort that cannot finish must never be able to",
                    "strand a player outside a cell with a sentence already running.")
                    .defineInRange("arrestEscortTimeoutTicks", 600, 0, 24000);
            escortTetherBlocks = b.comment(
                    "How far an arrested player may get from their escort before the arrest is abandoned",
                    "and they are marked resisting instead. Running from a surrender is a decision, so it",
                    "gets a consequence rather than a teleport back, which would read as a bug.")
                    .defineInRange("escortTetherBlocks", 16.0, 4.0, 64.0);
            escortLeashBlocks = b.comment(
                    "Soft radius: past this many blocks from the escorting guard an arrested player is",
                    "pulled back toward them. It is what makes the escort a lead rather than a suggestion.",
                    "Must stay comfortably below escortTetherBlocks, or the escort is abandoned before the",
                    "leash ever engages.")
                    .defineInRange("escortLeashBlocks", 5.0, 1.0, 32.0);
            escortSpeedPenalty = b.comment(
                    "How much of a restrained player's movement speed is taken away, as a fraction.",
                    "0.35 = they move at 65% of normal. Applied as an attribute modifier rather than a",
                    "potion effect, so it is not visible, not dispellable with milk, and emits no particles.")
                    .defineInRange("escortSpeedPenalty", 0.35, 0.0, 0.9);
            escortWalkSpeed = b.comment(
                    "How fast the guard walks while escorting a prisoner. Below 1.0 reads as a deliberate",
                    "march rather than a chase, and keeps the guard inside the leash radius.")
                    .defineInRange("escortWalkSpeed", 0.9, 0.1, 2.0);
            escortNavigationIntervalTicks = b.comment(
                    "How often the escort reissues its walk order. MCA villagers run their own brain, so a",
                    "navigation order issued every tick fights it and the guard visibly stutters; this is",
                    "reissued on a cadence and whenever the previous path finishes.")
                    .defineInRange("escortNavigationIntervalTicks", 20, 1, 200);
            escortStuckScans = b.comment(
                    "How many consecutive escort scans may pass without the prisoner getting closer to the",
                    "jail before the arrest is completed by teleport instead. This is the door, terrain and",
                    "pathfinding failsafe: a guard that cannot find its way must never be able to cancel a",
                    "sentence, only to finish it less gracefully.")
                    .defineInRange("escortStuckScans", 6, 1, 100);
            restrainedPlayerRestrictions = b.comment(
                    "While restrained, suppress attacking, interacting, breaking blocks, jumping, mounting,",
                    "and sprinting. Off leaves the escort and the visuals intact but lets a cuffed player",
                    "act normally, for servers that find the restriction too heavy-handed.")
                    .define("restrainedPlayerRestrictions", true);
            arrestRecoveryTicks = b.comment(
                    "How long, in online ticks, guards stand down after an arrest could not be completed",
                    "-- no cell, no sentence, or nowhere to put the prisoner. This is a state the arrest",
                    "genuinely reached, not a cooldown on the confrontation screen: without it a guard that",
                    "just failed to arrest somebody re-opens the same screen on the next scan and fails",
                    "again, which is what the repeating confrontation menu actually was.")
                    .defineInRange("arrestRecoveryTicks", 200, 20, 24000);
            jailAssignedMaxDistance = b.comment(
                    "How far (blocks) an operator-assigned jail may be from an arrest and still be used.",
                    "0 means unlimited, which is the historical behaviour and means a single",
                    "/crime assignjail anywhere in a dimension captures every arrest in it and permanently",
                    "suppresses holding-cell construction. Beyond this distance the arrest builds or falls",
                    "back locally instead. /crime jail is never distance-limited.")
                    .defineInRange("jailAssignedMaxDistance", 256.0, 0.0, 10_000.0);
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
            maxCasesPerFinePayment = b.comment(
                    "How many separate cases one fine payment may settle when it is not paying everything",
                    "off at once. Kept small so a single payment cannot quietly clear a long history.")
                    .defineInRange("maxCasesPerFinePayment", 3, 1, 20);
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

            b.push("weaponTrigger");
            weaponTriggerEnabled = b.comment(
                    "Right-clicking an MCA villager while holding a weapon opens the Crime menu.",
                    "While this is on, right-click gifting of a weapon to a villager is pre-empted: blacklist",
                    "the item under [weapons] or turn this off to gift it.")
                    .define("enabled", true);
            weaponTriggerRequireSneak = b.comment("Require sneaking as well as a weapon before the menu opens.")
                    .define("requireSneak", false);
            weaponTriggerAllowOffHand = b.comment("Also open the menu for an off-hand weapon interaction.")
                    .define("allowOffHand", true);
            b.pop();

            b.push("weapons");
            weaponWhitelist = b.comment(
                    "Items always treated as weapons. Entries are 'namespace:path' for an item or",
                    "'#namespace:path' for an item tag. No wildcards.",
                    "This list is COMMON config and is not synced: a client whose list differs from the",
                    "server's will mispredict the swing (the server still decides).")
                    .defineList("whitelist", List.of(), o -> o instanceof String);
            weaponBlacklist = b.comment(
                    "Items never treated as weapons, even if auto-detection or the whitelist would match.",
                    "Same 'namespace:path' / '#namespace:path' form; the blacklist always wins.")
                    .defineList("blacklist", List.of(), o -> o instanceof String);
            weaponAutoDetect = b.comment(
                    "Classify unlisted items automatically (swords, axes, tridents, bows, crossbows, guns).",
                    "Off means only the whitelist and the mcacrime:weapons tag count as weapons.")
                    .define("autoDetect", true);
            weaponAutoDetectMinAttackDamage = b.comment(
                    "Last-resort melee threshold: an unlisted item granting at least this much bonus attack",
                    "damage counts as a weapon. Vanilla's wooden sword grants 3.")
                    .defineInRange("autoDetectMinAttackDamage", 3.0, 0.0, 100.0);
            weaponGunKeywords = b.comment(
                    "Substrings in an item's registry path that mark it as a gun, for mods this list has",
                    "never heard of.")
                    .defineList("gunKeywords", List.of("gun", "rifle", "pistol", "revolver", "shotgun", "musket",
                            "blunderbuss", "smg", "carbine", "sniper", "launcher", "minigun"),
                            o -> o instanceof String);
            weaponMods = b.comment(
                    "Namespaces whose non-stackable, non-block items are assumed to be guns. These defaults",
                    "are editable guesses at the common gun mods, not a verified list.")
                    .defineList("weaponMods", List.of("tacz", "cgm", "pointblank", "scguns", "mwc"),
                            o -> o instanceof String);
            mugRequiresWeapon = b.comment("Mugging requires a weapon in one of your hands.")
                    .define("mugRequiresWeapon", true);
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
            villageTreasuryInitialBalance = b.comment("Finite initial emerald balance for a newly observed village treasury.")
                    .defineInRange("villageTreasuryInitialBalance", 64, 0, 1_000_000);
            b.pop();

            b.push("mugging");
            enableMugging = b.define("enableMugging", true);
            muggingBaseLoot = b.comment("Emeralds a villager 'pays' on a successful mugging.")
                    .defineInRange("muggingBaseLoot", 4, 0, 1_000_000);
            enableProfessionDeathDrops = b.comment("If true, a villager killed while resisting a mugging drops profession loot; default false favors robbery over murder (§8.6).")
                    .define("enableProfessionDeathDrops", false);
            muggingChannelTicks = b.comment("Overt threat channel duration before a mugging resolves.")
                    .defineInRange("muggingChannelTicks", 60, 1, 6000);
            muggingAttemptCooldownTicks = b.comment("Same actor/victim cooldown, stamped when the threat begins.")
                    .defineInRange("muggingAttemptCooldownTicks", 24000, 0, 10_000_000);
            muggingVictimRecoveryTicks = b.defineInRange("muggingVictimRecoveryTicks", 12000, 0, 10_000_000);
            muggingFearMemoryTicks = b.defineInRange("muggingFearMemoryTicks", 168000, 0, 100_000_000);
            muggingPanicTicks = b.comment("How long the direct victim actively flees; long-term fear remains memory/dialogue only.")
                    .defineInRange("muggingPanicTicks", 600, 0, 100_000);
            muggingActorSuccessCapPerDay = b.defineInRange("muggingActorSuccessCapPerDay", 4, 0, 10000);
            muggingActorValueCapPerDay = b.defineInRange("muggingActorValueCapPerDay", 12, 0, 1_000_000);
            muggingVillageValueCapPerDay = b.defineInRange("muggingVillageValueCapPerDay", 24, 0, 1_000_000);
            muggingPurseCapacity = b.defineInRange("muggingPurseCapacity", 5, 0, 1000);
            muggingPurseInitialMax = b.defineInRange("muggingPurseInitialMax", 3, 0, 1000);
            muggingPurseDailyIncome = b.defineInRange("muggingPurseDailyIncome", 1, 0, 1000);
            allowHostileActionsAgainstChildren = b.comment("Hostile person-to-person actions against children are hidden by default.")
                    .define("allowHostileActionsAgainstChildren", false);
            allowGameplayCommandFallback = b.comment("Keep /crime gameplay actions as accessibility fallbacks; they use the same action service.")
                    .define("allowGameplayCommandFallback", true);
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

            b.comment("Optional companion mods. Every setting here is a no-op when that mod is absent.")
                    .push("integrations");
            enableReputation = b.comment(
                    "Record community standing through MCA: Reputation when it is installed, instead of the",
                    "built-in per-village store. With this off, MCA: Crime keeps its own standing and MCA:",
                    "Reputation keeps detecting villager assault and killing itself -- there is never a state",
                    "where both record the same deed, or neither does.")
                    .define("enableReputation", true);
            mirrorReputationFallback = b.comment(
                    "After MCA: Reputation commits a standing change, copy the resulting score into the",
                    "built-in store. Costs nothing and means uninstalling Reputation later does not reset",
                    "every player to a stranger.")
                    .define("mirrorReputationFallback", true);
            suppressLocalVillagePenalty = b.comment(
                    "Skip the built-in village standing penalty for crimes MCA: Reputation is recording",
                    "canonically. Turning this off applies both, which double-counts every witnessed crime.")
                    .define("suppressLocalVillagePenalty", true);
            replayPendingOperations = b.comment(
                    "Retry cross-mod writes that were queued but not delivered -- after a crash, or while a",
                    "companion mod was uninstalled. Turning this off strands pending work indefinitely.")
                    .define("replayPendingOperations", true);
            pumpIntervalTicks = b.comment("How often the delivery queue is checked, in ticks.")
                    .defineInRange("pumpIntervalTicks", 100, 20, 12000);
            pumpBudgetPerTick = b.comment("How many queued writes may be delivered in one pass.")
                    .defineInRange("pumpBudgetPerTick", 8, 1, 128);
            maxDeliveryAttempts = b.comment(
                    "How many times a write is retried before it is set aside as a dead letter for an",
                    "operator to inspect with /crime debug outbox.")
                    .defineInRange("maxDeliveryAttempts", 6, 1, 20);
            retryBaseDelayTicks = b.comment("First retry delay; doubles on each failure up to the maximum.")
                    .defineInRange("retryBaseDelayTicks", 200, 20, 24000);
            retryMaxDelayTicks = b.comment("Ceiling on the retry delay. Must be >= retryBaseDelayTicks.")
                    .defineInRange("retryMaxDelayTicks", 24000, 20, 1_728_000);
            dedupeRetentionTicks = b.comment(
                    "How long a completed transaction is remembered so a replay of it changes nothing.",
                    "Long-lived links live on the crime record itself and never expire; this only covers",
                    "the replay window for one-off mutations.")
                    .defineInRange("dedupeRetentionTicks", 168_000, 1200, 1_728_000);
            b.push("reputation");
            fineResolutionStatus = b.comment(
                    "How a paid fine reads to the village: 'atoned' (made good) or 'apologized' (said sorry).")
                    .define("fineResolutionStatus", "atoned");
            servedResolutionStatus = b.comment(
                    "How a served sentence reads to the village: 'atoned' or 'apologized'.")
                    .define("servedResolutionStatus", "atoned");
            b.pop();
            b.pop();
        }
    }

    public static final class Client {
        public final ForgeConfigSpec.BooleanValue nameColorEnabled;
        public final ForgeConfigSpec.EnumValue<NameColorMode> nameColorMode;
        public final ForgeConfigSpec.BooleanValue showPlayerCardButton;
        public final ForgeConfigSpec.BooleanValue showButtonOnMcaScreen;
        public final ForgeConfigSpec.BooleanValue playerCardOpenByDefault;
        public final ForgeConfigSpec.BooleanValue captiveScreenToggle;
        public final ForgeConfigSpec.BooleanValue confirmHostileActions;
        public final ForgeConfigSpec.BooleanValue hudEnabled;
        public final ForgeConfigSpec.BooleanValue hudChannelBar;
        public final ForgeConfigSpec.BooleanValue hudStatusIndicator;
        public final ForgeConfigSpec.BooleanValue hudCustodyIndicator;
        public final ForgeConfigSpec.BooleanValue renderRestraintPose;
        public final ForgeConfigSpec.BooleanValue renderCuffs;
        public final ForgeConfigSpec.BooleanValue renderEscortRope;
        public final ForgeConfigSpec.EnumValue<dev.otectus.mcacrime.client.hud.HudAnchor> hudAnchor;
        public final ForgeConfigSpec.IntValue hudOffsetX;
        public final ForgeConfigSpec.IntValue hudOffsetY;

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
            showButtonOnMcaScreen = b.comment("Add the Crime button to MCA's own villager interaction screen.")
                    .define("showButtonOnMcaScreen", true);
            captiveScreenToggle = b.comment("Show the captive panel when you are being held.")
                    .define("captiveScreenToggle", true);
            confirmHostileActions = b.comment("Ask for confirmation before a hostile action. "
                            + "Presentation only: the server validates every action either way.")
                    .define("confirmHostileActions", true);
            hudEnabled = b.comment("Master switch for the on-screen HUD. Off returns every message to chat.")
                    .define("hudEnabled", true);
            hudChannelBar = b.comment("Show the action channel bar, and why an action broke off.")
                    .define("hudChannelBar", true);
            hudStatusIndicator = b.comment("Show Heat and Wanted status. Hidden entirely when you have neither.")
                    .define("hudStatusIndicator", true);
            hudCustodyIndicator = b.comment("Show the remaining jail sentence or captivity time.")
                    .define("hudCustodyIndicator", true);
            renderRestraintPose = b.comment(
                    "Pose a restrained player's arms behind their back. Presentation only -- turning it off",
                    "changes nothing the server knows or allows.")
                    .define("renderRestraintPose", true);
            renderCuffs = b.comment("Draw cuffs on a restrained player's wrists.")
                    .define("renderCuffs", true);
            renderEscortRope = b.comment("Draw the lead between an escorting guard and their prisoner.")
                    .define("renderEscortRope", true);
            hudAnchor = b.comment("Which screen corner or edge the status and custody boxes sit against.",
                            "Bottom anchors are lifted clear of the hotbar and health rows automatically.",
                            "The channel bar always sits above the hotbar, where the eye already is.")
                    .defineEnum("hudAnchor", dev.otectus.mcacrime.client.hud.HudAnchor.BOTTOM_LEFT);
            hudOffsetX = b.comment("Horizontal nudge inward from the anchored edge, in pixels -- right from a",
                            "left anchor, left from a right one. Clamped on screen.")
                    .defineInRange("hudOffsetX", 4, -4096, 4096);
            hudOffsetY = b.comment("Vertical nudge inward from the anchored edge, in pixels -- down from a top",
                            "anchor, up from a bottom one. Clamped on screen.")
                    .defineInRange("hudOffsetY", 4, -4096, 4096);
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
