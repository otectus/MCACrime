package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailContainmentMode;
import dev.otectus.mcacrime.mug.npc.TheftPolicy;
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
        public final ForgeConfigSpec.BooleanValue enableWitnessSystem;
        public final ForgeConfigSpec.DoubleValue visualWitnessRadiusMultiplier;
        public final ForgeConfigSpec.DoubleValue auditoryWitnessRadiusMultiplier;
        public final ForgeConfigSpec.BooleanValue enableWitnessGossip;
        public final ForgeConfigSpec.BooleanValue enableDynamicCompliance;
        public final ForgeConfigSpec.BooleanValue enablePanic;
        public final ForgeConfigSpec.BooleanValue enableStalling;
        public final ForgeConfigSpec.BooleanValue enablePleading;
        public final ForgeConfigSpec.IntValue threatReevaluationTicks;
        public final ForgeConfigSpec.DoubleValue meleeThreatRange;
        public final ForgeConfigSpec.DoubleValue rangedThreatRange;
        public final ForgeConfigSpec.BooleanValue enableVictimMemory;
        public final ForgeConfigSpec.BooleanValue enableFamilyMemory;
        public final ForgeConfigSpec.BooleanValue enableMemoryRestitution;
        public final ForgeConfigSpec.BooleanValue enableApologies;
        public final ForgeConfigSpec.DoubleValue memoryDecayMultiplier;
        public final ForgeConfigSpec.IntValue maximumMemoriesPerVillager;
        public final ForgeConfigSpec.IntValue apologyCooldownTicks;

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
        public final ForgeConfigSpec.DoubleValue civilianCrimeReactionSpeedMultiplier;
        public final ForgeConfigSpec.BooleanValue freezeComplyingVictims;
        public final ForgeConfigSpec.BooleanValue armedVillagersCanResist;
        public final ForgeConfigSpec.DoubleValue complianceResistThreshold;
        public final ForgeConfigSpec.DoubleValue complianceHelpThreshold;

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
        public final ForgeConfigSpec.DoubleValue guardThiefResponseRadius;
        public final ForgeConfigSpec.IntValue guardThiefPursuitTimeoutTicks;
        public final ForgeConfigSpec.BooleanValue guardsUseForceOnArmedThieves;
        public final ForgeConfigSpec.BooleanValue returnStolenGoodsOnArrest;
        public final ForgeConfigSpec.DoubleValue stolenGoodsReturnRadius;
        public final ForgeConfigSpec.IntValue npcEscortOrphanTicks;
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
        public final ForgeConfigSpec.IntValue escapeWorkTicksLockedCuffs;
        public final ForgeConfigSpec.BooleanValue cuffEscapeRequiresLockpick;
        public final ForgeConfigSpec.IntValue escapeAttemptCooldownTicks;

        // NPC crime (§9) — skeleton
        public final ForgeConfigSpec.BooleanValue enableNpcCrime;
        public final ForgeConfigSpec.IntValue maxActiveNpcCrimesPerVillage;
        public final ForgeConfigSpec.IntValue minTimeBetweenNpcCrimes;
        public final ForgeConfigSpec.IntValue npcMugHudUpdateIntervalTicks;
        public final ForgeConfigSpec.IntValue npcMugWeaponCheckIntervalTicks;

        // criminal jobs (0.5.1) — read by the job package
        public final ForgeConfigSpec.BooleanValue enableThieves;
        public final ForgeConfigSpec.BooleanValue enableFences;
        public final ForgeConfigSpec.DoubleValue villageThiefChance;
        public final ForgeConfigSpec.DoubleValue villageFenceChance;
        public final ForgeConfigSpec.DoubleValue wildThiefChance;
        public final ForgeConfigSpec.IntValue minVillagePopulationForFence;
        public final ForgeConfigSpec.IntValue criminalAssignmentCooldownDays;
        public final ForgeConfigSpec.IntValue assignmentScanIntervalTicks;
        public final ForgeConfigSpec.BooleanValue presentFenceAsMcaProfession;
        public final ForgeConfigSpec.BooleanValue presentThiefAsMcaProfession;
        public final ForgeConfigSpec.IntValue staleRecordGraceDays;

        // criminalJobs.thief (0.5.1) -- read by the ai/thief package through ThiefPolicy
        public final ForgeConfigSpec.IntValue thiefMugDurationTicks;
        public final ForgeConfigSpec.IntValue thiefMugCooldownTicks;
        public final ForgeConfigSpec.IntValue thiefMugProtectionHearts;
        public final ForgeConfigSpec.IntValue thiefScanIntervalTicks;
        public final ForgeConfigSpec.DoubleValue thiefTargetSearchRadius;
        public final ForgeConfigSpec.DoubleValue thiefGuardAvoidRadius;
        public final ForgeConfigSpec.DoubleValue thiefGuardHardAbortRadius;
        public final ForgeConfigSpec.DoubleValue thiefGuardRiskAbortThreshold;
        public final ForgeConfigSpec.IntValue thiefMinCurrencySteal;
        public final ForgeConfigSpec.IntValue thiefMaxCurrencySteal;
        public final ForgeConfigSpec.BooleanValue thiefStealAllIfBelowMinimum;
        public final ForgeConfigSpec.BooleanValue thiefProtectHotbar;
        public final ForgeConfigSpec.BooleanValue thiefProtectArmor;
        public final ForgeConfigSpec.BooleanValue thiefProtectOffhand;
        public final ForgeConfigSpec.EnumValue<TheftPolicy.ItemTheftMode> thiefItemTheftMode;
        public final ForgeConfigSpec.IntValue thiefStolenGoodsPersistenceDays;
        public final ForgeConfigSpec.IntValue thiefJailTicks;

        // criminalJobs.fence (0.5.1) -- read by economy/fence through FencePolicy
        public final ForgeConfigSpec.DoubleValue fenceMaxKarmaDiscount;
        public final ForgeConfigSpec.DoubleValue fenceMaxHeatMarkup;
        public final ForgeConfigSpec.DoubleValue fenceWantedMarkup;
        public final ForgeConfigSpec.DoubleValue fenceMinimumPriceMultiplier;
        public final ForgeConfigSpec.DoubleValue fenceMaximumPriceMultiplier;
        public final ForgeConfigSpec.DoubleValue fenceBuyPriceRatio;
        public final ForgeConfigSpec.IntValue fenceDefaultBasePrice;
        public final ForgeConfigSpec.IntValue fenceOfferCount;
        public final ForgeConfigSpec.IntValue fenceOfferMaxUses;
        public final ForgeConfigSpec.IntValue fenceRestockIntervalDays;

        // bounty (0.5.1) -- read by the bounty package
        public final ForgeConfigSpec.BooleanValue bountyEnabled;
        public final ForgeConfigSpec.IntValue baseBounty;
        public final ForgeConfigSpec.IntValue minBounty;
        public final ForgeConfigSpec.IntValue maxBounty;
        public final ForgeConfigSpec.DoubleValue severityRewardScale;
        public final ForgeConfigSpec.DoubleValue fineRewardShare;
        public final ForgeConfigSpec.IntValue repeatOffenderBonus;
        public final ForgeConfigSpec.BooleanValue payForKills;
        public final ForgeConfigSpec.BooleanValue payForAliveCapture;
        public final ForgeConfigSpec.DoubleValue killMultiplier;
        public final ForgeConfigSpec.DoubleValue aliveCaptureMultiplier;
        public final ForgeConfigSpec.IntValue bountyKarmaReward;
        public final ForgeConfigSpec.BooleanValue redBandBountyEligible;
        public final ForgeConfigSpec.IntValue claimRetentionDays;
        public final ForgeConfigSpec.DoubleValue bountyDeliveryRadius;

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
        public final ForgeConfigSpec.BooleanValue requireWeaponForCrimeMenu;
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
        public final ForgeConfigSpec.BooleanValue dropVillagerEquipment;
        public final ForgeConfigSpec.BooleanValue dropVillagerTradeStock;
        public final ForgeConfigSpec.IntValue maxTradeDeathDropStacks;
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
        public final ForgeConfigSpec.ConfigValue<String> currencyId;
        public final ForgeConfigSpec.BooleanValue locksReforgedFenceTrades;
        public final ForgeConfigSpec.BooleanValue mcaQuestsBounties;
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
            wantedHeatThreshold = b.comment(
                    "Heat at or above this makes a player Wanted. Nearby available guards and archers",
                    "pursue and challenge them even without a crime report, including Heat set by commands.")
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
            civilianCrimeReactionSpeedMultiplier = b.comment(
                    "Movement speed of an unarmed villager while this mod is steering them, as a fraction of",
                    "normal. A frightened farmer who runs at trading speed reads as a bug. Applied as a",
                    "transient attribute modifier and removed on every exit path, so it can never persist.")
                    .defineInRange("civilianCrimeReactionSpeedMultiplier", 0.65, 0.10, 1.00);
            freezeComplyingVictims = b.comment(
                    "Whether an unarmed villager holds still while a coercive action (a mugging, a capture)",
                    "is actually running against them. It takes a live session naming that villager -- being",
                    "near an armed player never freezes anybody. Off makes them run instead.")
                    .define("freezeComplyingVictims", true);
            armedVillagersCanResist = b.comment(
                    "Whether guards, archers, weapon-holders and tagged combatants fight back instead of",
                    "complying. Off means even a guard can be mugged, which is a legitimate but very",
                    "different game.")
                    .define("armedVillagersCanResist", true);
            complianceResistThreshold = b.comment(
                    "Bravery at or above which an unthreatened villager fights rather than runs.")
                    .defineInRange("complianceResistThreshold", 0.6, 0.0, 1.0);
            complianceHelpThreshold = b.comment(
                    "Score at or above which a villager goes and fetches a responder rather than running,",
                    "when there is one to fetch.")
                    .defineInRange("complianceHelpThreshold", 0.5, 0.0, 1.0);
            b.pop();

            b.push("crimeAwareness");
            enableWitnessSystem = b.define("enableWitnessSystem", true);
            visualWitnessRadiusMultiplier = b.defineInRange("visualWitnessRadiusMultiplier", 1.0, 0.0, 2.0);
            auditoryWitnessRadiusMultiplier = b.defineInRange("auditoryWitnessRadiusMultiplier", 1.0, 0.0, 2.0);
            enableWitnessGossip = b.comment("Allow a direct witness to inform one nearby relative after a delay.")
                    .define("enableWitnessGossip", true);
            b.pop();
            b.push("intimidation");
            enableDynamicCompliance = b.define("enableDynamicCompliance", true);
            enablePanic = b.define("enablePanic", true);
            enableStalling = b.define("enableStalling", true);
            enablePleading = b.define("enablePleading", true);
            threatReevaluationTicks = b.defineInRange("threatReevaluationTicks", 10, 5, 100);
            meleeThreatRange = b.defineInRange("meleeThreatRange", 6.0, 1.0, 12.0);
            rangedThreatRange = b.defineInRange("rangedThreatRange", 24.0, 4.0, 64.0);
            b.pop();
            b.push("victimMemory");
            enableVictimMemory = b.define("enableVictimMemory", true);
            enableFamilyMemory = b.define("enableFamilyMemory", true);
            enableMemoryRestitution = b.define("enableRestitution", true);
            enableApologies = b.define("enableApologies", true);
            memoryDecayMultiplier = b.defineInRange("memoryDecayMultiplier", 1.0, 0.0, 10.0);
            maximumMemoriesPerVillager = b.defineInRange("maximumMemoriesPerVillager", 24, 1, 64);
            apologyCooldownTicks = b.defineInRange("apologyCooldownTicks", 24000, 1200, 168000);
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
            raidGrace = b.comment("Forgive one nonlethal indirect explosion against a protected non-player, non-responder per combat encounter during an active raid. Direct/repeated attacks and killing remain crimes.")
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
                    "Guards and archers challenge Wanted players or reported suspects before attacking.",
                    "They state the reason and open a window to surrender, pay, or refuse.",
                    "Off returns guards to attacking a Wanted player",
                    "on sight, which is the behaviour this replaces.")
                    .define("enableGuardChallenge", true);
            guardChallengeWindowTicks = b.comment(
                    "How long a challenged player has to answer. No answer is a refusal, not a pardon.",
                    "At least 300 ticks (15 seconds), starting when the menu is displayed; delivery grace is bounded.")
                    .defineInRange("guardChallengeWindowTicks", 300, 300, 12000);
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
            guardThiefResponseRadius = b.comment(
                    "How far a guard will notice a mugging in progress. Line of sight is required as",
                    "well, so this is the range at which a guard who can already see the threat reacts",
                    "to it -- not a radius within which guards become psychic.")
                    .defineInRange("guardThiefResponseRadius", 24.0, 4.0, 64.0);
            guardThiefPursuitTimeoutTicks = b.comment(
                    "How long a guard chases a thief before giving up. The chase also ends when the",
                    "thief gets further away than guardAggroRadius.")
                    .defineInRange("guardThiefPursuitTimeoutTicks", 600, 40, 24_000);
            guardsUseForceOnArmedThieves = b.comment(
                    "An armed thief is fought rather than merely chased. Off makes every arrest a",
                    "non-lethal capture, which is safer for the thief and slower for the guard.")
                    .define("guardsUseForceOnArmedThieves", true);
            returnStolenGoodsOnArrest = b.comment(
                    "A guard hands back what the thief took, to any victim standing nearby. Off leaves",
                    "killing the prisoner as the only way to recover property, which is exactly the",
                    "incentive this exists to remove.")
                    .define("returnStolenGoodsOnArrest", true);
            stolenGoodsReturnRadius = b.comment(
                    "How close a victim must be to the arrest to be handed their property back. Owners",
                    "further away keep their claim: the ledger entry is untouched.")
                    .defineInRange("stolenGoodsReturnRadius", 16.0, 1.0, 64.0);
            npcEscortOrphanTicks = b.comment(
                    "How long a restrained thief waits for a replacement escort after its guard dies or",
                    "wanders off, before being jailed where it stands. This is the backstop against a",
                    "cuffed villager standing in a field forever.")
                    .defineInRange("npcEscortOrphanTicks", 1200, 100, 24_000);
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
            restraintEscapeChanceLockedCuffs = b.comment("Without Locks Reforged, 0 disables timed escape from locked cuffs. With Locks installed, cuffs use its minigame instead.")
                    .defineInRange("restraintEscapeChanceLockedCuffs", 0.0, 0.0, 1.0);
            captiveTetherBlocks = b.comment("How far (blocks) a captive may stray from the hold point before being tethered back or (if allowed) escaping.")
                    .defineInRange("captiveTetherBlocks", 6.0, 1.0, 128.0);
            captiveCanEscapeByDistance = b.comment("If true, a kidnapping captive who strays past the tether escapes (no crime); if false they are pulled back.",
                            "Cuffs always tether back when Locks Reforged is installed: self-escape requires solving their lock.")
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
            escapeWorkTicksLockedCuffs = b.comment("Work duration for locked cuffs when Locks Reforged is absent and their escape chance is nonzero.")
                    .defineInRange("escapeWorkTicksLockedCuffs", 1200, 1, 72000);
            cuffEscapeRequiresLockpick = b.comment("With Locks Reforged installed, require a lockpick anywhere in the inventory for cuff lockpicking.",
                    "False permits the native minigame without an item. Both cuff types always require winning the minigame when Locks is present.")
                    .define("cuffEscapeRequiresLockpick", false);
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
            npcMugHudUpdateIntervalTicks = b.comment(
                    "Ticks between progress packets for a thief's mugging bar. The client interpolates",
                    "between them, so this is packet volume rather than smoothness.")
                    .defineInRange("npcMugHudUpdateIntervalTicks", 3, 1, 20);
            npcMugWeaponCheckIntervalTicks = b.comment(
                    "Ticks between weapon checks on a mugging victim. 1 is every tick, which is what the",
                    "counterplay deserves: drawing a sword should stop the mug now, not in a moment.")
                    .defineInRange("npcMugWeaponCheckIntervalTicks", 1, 1, 10);
            b.pop();

            b.comment(
                    "Thief and Fence occupations, persisted by this mod rather than by MCA. A criminal job",
                    "survives arrest: going to jail does not stop somebody being a thief.")
                    .push("criminalJobs");
            enableThieves = b.define("enableThieves", true);
            enableFences = b.define("enableFences", true);
            villageThiefChance = b.comment("Chance an eligible village adult is made a thief when the sweep considers them.")
                    .defineInRange("villageThiefChance", 0.025D, 0.0D, 1.0D);
            villageFenceChance = b.comment("As above, for fences. Fences belong to settlements, not the wilderness.")
                    .defineInRange("villageFenceChance", 0.010D, 0.0D, 1.0D);
            wildThiefChance = b.comment("Chance for a villager with no home village. Independent criminals should be rare.")
                    .defineInRange("wildThiefChance", 0.0025D, 0.0D, 1.0D);
            minVillagePopulationForFence = b.comment("A village smaller than this never produces a fence.")
                    .defineInRange("minVillagePopulationForFence", 5, 1, 200);
            criminalAssignmentCooldownDays = b.comment("Days a village waits after producing one criminal before it may produce another.")
                    .defineInRange("criminalAssignmentCooldownDays", 3, 0, 365);
            assignmentScanIntervalTicks = b.comment("Server ticks between assignment passes.")
                    .defineInRange("assignmentScanIntervalTicks", 1200, 200, 24000);
            presentFenceAsMcaProfession = b.comment(
                    "Show a fence as the 'mcacrime:fence' villager profession. On by default: a fence",
                    "nobody can identify is a shop with no sign. The previous profession is remembered and",
                    "restored if this is turned off again.")
                    .define("presentFenceAsMcaProfession", true);
            presentThiefAsMcaProfession = b.comment(
                    "Show a thief as the 'mcacrime:thief' villager profession. Off by default: a thief",
                    "wearing a label has no cover. Turn it on for a pack that wants criminals legible.")
                    .define("presentThiefAsMcaProfession", false);
            staleRecordGraceDays = b.comment("Days a criminal record is kept after the villager was last seen loaded.")
                    .defineInRange("staleRecordGraceDays", 14, 1, 365);

            b.comment(
                    "How a thief goes about a mugging. Radii are in blocks and every interval is in",
                    "server ticks; the scan interval is jittered by a quarter either way so thieves in",
                    "one village never all look around on the same tick.")
                    .push("thief");
            thiefMugDurationTicks = b.comment("How long the victim's bar takes to fill. Four seconds by default.")
                    .defineInRange("mugDurationTicks", 80, 20, 600);
            thiefMugCooldownTicks = b.comment("How long a thief waits after one mugging before looking for another.")
                    .defineInRange("mugCooldownTicks", 12000, 0, 240000);
            thiefMugProtectionHearts = b.comment(
                    "A villager will not mug a player whose MCA relationship hearts with that villager",
                    "are at or above this threshold. Checked during approach and throughout the mug,",
                    "including before theft. 0 protects neutral and positive relationships; -1 disables",
                    "only this relationship protection. Uses hearts, not Karma or village reputation.")
                    .defineInRange("mugProtectionHearts", 50, -1, 1000);
            thiefScanIntervalTicks = b.comment("Ticks between a scouting thief's target scans.")
                    .defineInRange("scanIntervalTicks", 30, 10, 200);
            thiefTargetSearchRadius = b.comment("How far a thief will consider a victim.")
                    .defineInRange("targetSearchRadius", 20.0D, 4.0D, 64.0D);
            thiefGuardAvoidRadius = b.comment("Guards within this distance contribute to the thief's risk score.")
                    .defineInRange("guardAvoidRadius", 16.0D, 0.0D, 64.0D);
            thiefGuardHardAbortRadius = b.comment(
                    "A guard this close is a problem whatever else is true: the risk score jumps and the",
                    "thief breaks off rather than robbing somebody beside the police station.")
                    .defineInRange("guardHardAbortRadius", 8.0D, 0.0D, 64.0D);
            thiefGuardRiskAbortThreshold = b.comment("Risk score at or above which a target is refused and an approach broken off.")
                    .defineInRange("guardRiskAbortThreshold", 0.6D, 0.0D, 1.0D);
            thiefMinCurrencySteal = b.comment(
                    "Smallest amount of the active currency one mugging takes, when there is that much",
                    "to take.")
                    .defineInRange("minCurrencySteal", 1, 0, 1_000_000);
            thiefMaxCurrencySteal = b.comment("Largest amount one mugging takes. Must not be below the minimum.")
                    .defineInRange("maxCurrencySteal", 8, 0, 1_000_000);
            thiefStealAllIfBelowMinimum = b.comment(
                    "When the victim holds less than minCurrencySteal, take all of it. Off instead skips",
                    "currency entirely and falls through to the item, so a thief who will not take four",
                    "emeralds takes a spare pickaxe.")
                    .define("stealAllIfBelowMinimum", true);
            thiefProtectHotbar = b.comment("Thieves never reach into the hotbar. On by default: what you are holding is yours.")
                    .define("protectHotbar", true);
            thiefProtectArmor = b.comment("Thieves never take worn armor.")
                    .define("protectArmor", true);
            thiefProtectOffhand = b.comment("Thieves never take the offhand item.")
                    .define("protectOffhand", true);
            thiefItemTheftMode = b.comment(
                    "How much of the chosen slot goes: SINGLE_ITEM takes one count, WHOLE_STACK takes",
                    "the slot, RANDOM_COUNT takes somewhere between. SINGLE_ITEM is the default because",
                    "it is the only one whose punishment does not depend on how the victim stacked.")
                    .defineEnum("itemTheftMode", TheftPolicy.ItemTheftMode.SINGLE_ITEM);
            thiefStolenGoodsPersistenceDays = b.comment(
                    "Days a stolen item stays attributable to its owner before it is laundered out of",
                    "the world data. 0 keeps stolen goods forever. Goods are never expired out from",
                    "under a thief who is still mugging or fleeing.")
                    .defineInRange("stolenGoodsPersistenceDays", 7, 0, 365);
            thiefJailTicks = b.comment(
                    "How long an arrested thief serves. Ten minutes by default. A criminal job survives",
                    "the sentence: a thief comes out of jail still a thief.")
                    .defineInRange("thiefJailTicks", 12000, 200, 240_000);
            b.pop();

            b.comment(
                    "What a fence charges. Karma and Heat are deliberately not collapsed into one",
                    "number: Karma says whether the player is one of us, Heat says how much attention",
                    "doing business with them attracts, and a notorious outlaw the guards are actively",
                    "hunting pays the surcharge despite the discount.")
                    .push("fence");
            fenceMaxKarmaDiscount = b.comment(
                    "Largest discount criminal standing earns, at the Red band threshold. 0.25 = 25% off.")
                    .defineInRange("maxKarmaDiscount", 0.25D, 0.0D, 1.0D);
            fenceMaxHeatMarkup = b.comment(
                    "Largest surcharge Heat adds, at the Wanted threshold. Stacks with the discount above.")
                    .defineInRange("maxHeatMarkup", 0.35D, 0.0D, 1.0D);
            fenceWantedMarkup = b.comment("Flat surcharge added on top while the player is Wanted.")
                    .defineInRange("wantedMarkup", 0.20D, 0.0D, 1.0D);
            fenceMinimumPriceMultiplier = b.comment(
                    "Floor on the combined multiplier. Must be below maximumPriceMultiplier.")
                    .defineInRange("minimumPriceMultiplier", 0.55D, 0.05D, 1.0D);
            fenceMaximumPriceMultiplier = b.comment("Ceiling on the combined multiplier.")
                    .defineInRange("maximumPriceMultiplier", 2.50D, 1.0D, 10.0D);
            fenceBuyPriceRatio = b.comment(
                    "What a fence pays for goods, as a fraction of what it sells them for. Always",
                    "resolved to strictly less than the sale price, so buying and re-selling the same",
                    "item can never turn a profit.")
                    .defineInRange("buyPriceRatio", 0.5D, 0.05D, 0.95D);
            fenceDefaultBasePrice = b.comment(
                    "Price used for contraband that a tag names but no fence_prices file gives a value.")
                    .defineInRange("defaultBasePrice", 8, 1, 100000);
            fenceOfferCount = b.comment("How many trades one fence offers at a time.")
                    .defineInRange("offerCount", 6, 1, 12);
            fenceOfferMaxUses = b.comment(
                    "How many times one of a fence's trades may be repeated before that stock runs out.",
                    "Uses are persisted per fence and survive closing the screen, relogging and a",
                    "restart; they reset when the fence restocks.")
                    .defineInRange("offerMaxUses", 8, 1, 4096);
            fenceRestockIntervalDays = b.comment(
                    "In-game days a fence keeps the same stock. 0 re-rolls it every time it is opened.")
                    .defineInRange("restockIntervalDays", 1, 0, 30);
            b.pop();
            b.pop();

            b.comment(
                    "Prices on the heads of outlaws, and how they may be collected.",
                    "",
                    "A bounty is priced off the ledger, not off the kill: what somebody is worth is what",
                    "they have outstanding. Payment is keyed on (target, warrant, revision), so one",
                    "wanted state pays exactly once however many times the target dies.")
                    .push("bounty");
            bountyEnabled = b.define("enabled", true);
            baseBounty = b.comment("What a freshly Wanted outlaw is worth before their record is counted.")
                    .defineInRange("baseBounty", 8, 0, 1_000_000);
            minBounty = b.comment("Floor on the payout. A bounty worth nothing is a bounty nobody hunts.")
                    .defineInRange("minBounty", 1, 0, 1_000_000);
            maxBounty = b.comment("Ceiling on the payout, so a career criminal is not a jackpot.")
                    .defineInRange("maxBounty", 128, 0, 1_000_000);
            severityRewardScale = b.comment("Multiplier on the Heat of the target's unresolved cases.")
                    .defineInRange("severityRewardScale", 2.0D, 0.0D, 100.0D);
            fineRewardShare = b.comment("Fraction of the target's outstanding fines folded into the price.")
                    .defineInRange("fineRewardShare", 0.25D, 0.0D, 1.0D);
            repeatOffenderBonus = b.comment("Added per warrant already closed against the target.")
                    .defineInRange("repeatOffenderBonus", 4, 0, 100_000);
            payForKills = b.comment("Whether killing a bounty-eligible outlaw pays.")
                    .define("payForKills", true);
            payForAliveCapture = b.comment(
                    "Whether taking a bounty-eligible outlaw alive pays. This is also the switch that",
                    "makes restraining one a citizen's arrest rather than a kidnapping: with it off,",
                    "cuffing an outlaw is the crime it has always been.")
                    .define("payForAliveCapture", true);
            killMultiplier = b.defineInRange("killMultiplier", 1.0D, 0.0D, 10.0D);
            aliveCaptureMultiplier = b.comment(
                    "Alive is worth more than dead by default, which is the entire reason the restraint",
                    "and jail mechanics are worth a hunter's trouble.")
                    .defineInRange("aliveCaptureMultiplier", 1.25D, 0.0D, 10.0D);
            bountyKarmaReward = b.comment("Karma granted to the claimant. Bounty hunting is lawful work.")
                    .defineInRange("karmaReward", 2, 0, 50);
            redBandBountyEligible = b.comment(
                    "Whether a Red-band player with no Wanted status can carry a bounty. Off by default:",
                    "a reputation is not a warrant, and redIsLegalTarget already decides whether it is",
                    "grounds for force.")
                    .define("redBandEligible", false);
            claimRetentionDays = b.comment("In-game days a paid claim is remembered before it is forgotten.")
                    .defineInRange("claimRetentionDays", 30, 1, 3650);
            bountyDeliveryRadius = b.comment(
                    "How close a hunter holding an outlaw must bring them to a guard for the arrest to",
                    "count as a delivery.")
                    .defineInRange("deliveryRadius", 4.0D, 1.0D, 16.0D);
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
                    "Walking pace relative to normal villager navigation. MCA's raw navigation multiplier",
                    "is halved, with an effective movement cap; the guard waits before the lead gets taut.")
                    .defineInRange("escortWalkSpeed", 0.9, 0.1, 2.0);
            escortNavigationIntervalTicks = b.comment(
                    "How often the escort reissues its walk order. MCA villagers run their own brain, so a",
                    "navigation order issued every tick fights it and the guard visibly stutters; this is",
                    "reissued on a cadence and whenever the previous path finishes.")
                    .defineInRange("escortNavigationIntervalTicks", 20, 1, 200);
            escortStuckScans = b.comment(
                    "How many consecutive escort scans may pass without either guard or prisoner moving toward the",
                    "next part of the route before intake completes by teleport. Detours count as progress.",
                    "This is the door, terrain and",
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
            requireWeaponForCrimeMenu = b.comment(
                    "Require a drawn weapon for coercive entries in the Crime menu, however it was reached.",
                    "Peaceful options such as apologies remain reachable with empty hands. Turning this",
                    "off removes the menu weapon gate; individual action requirements still apply.")
                    .define("requireWeaponForCrimeMenu", true);
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

            b.push("loot");
            dropVillagerEquipment = b.comment("Drop actual equipped items on MCA villager death, preserving item data and respecting vanishing enchantments. MCA owns carried inventory drops.")
                    .define("dropEquipment", true);
            dropVillagerTradeStock = b.comment("Drop one purchase worth of output from each unlocked, non-exhausted trade on MCA villager death, including fence goods. Respects doMobLoot.")
                    .define("dropTradeStock", true);
            maxTradeDeathDropStacks = b.comment("Maximum item stacks from trade stock per death. Bounds unusually large modded offers; equipment is separate.")
                    .defineInRange("maxTradeDropStacks", 128, 1, 4096);
            b.pop();

            b.push("mugging");
            enableMugging = b.define("enableMugging", true);
            muggingBaseLoot = b.comment("Emeralds a villager 'pays' on a successful mugging.")
                    .defineInRange("muggingBaseLoot", 4, 0, 1_000_000);
            enableProfessionDeathDrops = b.comment("Legacy small profession drops for player kills, only when loot.dropTradeStock is disabled. Actual equipment and trade stock use the loot section.")
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
            currencyId = b.comment(
                    "Which registered currency fines, bail, ransom, theft and bounties are paid in.",
                    "'mcacrime:emerald' is built in; an economy mod registers its own id. An id nothing",
                    "has registered falls back to emeralds with one warning rather than taking the",
                    "economy offline.")
                    .define("currencyId", "mcacrime:emerald");
            locksReforgedFenceTrades = b.comment(
                    "Let fences stock Locks Reforged locks, picks and keys when that mod is installed.",
                    "A no-op without it: nothing here names a Locks class, and the goods are looked up",
                    "by registry id.")
                    .define("locksReforgedFenceTrades", true);
            mcaQuestsBounties = b.comment(
                    "Publish open bounties as MCA: Quests contracts when that mod is installed. A bounty",
                    "is paid once whichever route claims it.")
                    .define("mcaQuestsBounties", true);
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
        public final ForgeConfigSpec.BooleanValue showNpcMuggingHud;
        public final ForgeConfigSpec.EnumValue<CrimeButtonAnchor> crimeButtonAnchor;
        public final ForgeConfigSpec.EnumValue<dev.otectus.mcacrime.client.hud.HudAnchor> hudAnchor;
        public final ForgeConfigSpec.IntValue hudLayoutVersion;
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
            crimeButtonAnchor = b.comment(
                    "Where the Crime button sits on MCA's interaction screen. BOTTOM measures MCA's own",
                    "widgets and drops in underneath them; TOP_RIGHT is the corner placement used before",
                    "0.5.1, kept for MCA builds whose panel reaches the bottom of the screen.")
                    .defineEnum("crimeButtonAnchor", CrimeButtonAnchor.BOTTOM);
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
            showNpcMuggingHud = b.comment(
                    "Show the bar and the hint line while a thief is mugging you. Presentation only --",
                    "with it off the mugging still runs, and drawing a weapon still stops it.")
                    .define("showNpcMuggingHud", true);
            hudLayoutVersion = b.comment("HUD migration marker; managed automatically.")
                    .defineInRange("hudLayoutVersion", 0, 0, 1);
            hudAnchor = b.comment("Which screen corner or edge the combined Heat and Sentence panel sits against.",
                            "BOTTOM_LEFT fits below chat and to the left of the hotbar, scaling down if needed.",
                            "The panel hides while typing in chat. Other bottom anchors clear the health rows.",
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

    /** Where the Crime button is anchored on MCA's interaction screen (0.5.1). */
    public enum CrimeButtonAnchor {
        BOTTOM,
        TOP_RIGHT
    }

    /** How band name coloring is applied (spec §10.3). */
    public enum NameColorMode {
        FULL,
        PREFIX_ONLY
    }
}
