package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.CrimeIncidentMapping;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Config validation (spec §12.3). The core checks are a pure function of plain values, so they are
 * unit-testable without a running game. The headline gate-check is band ordering (Blue threshold must be
 * above Red). Entity-id list entries are parse-checked here; their <em>registry</em> existence is an
 * additional best-effort pass in {@link #validateCurrentConfig()} (registries only exist at runtime).
 *
 * <p>Checks deferred to later phases (no datapack/MCA loaded yet): tag existence, malformed crime JSON,
 * missing jail structures, unknown profession IDs, impossible ransom/punishment tables.
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    /**
     * Pure validation of the values that matter today. Returns a (possibly empty) list of
     * human-readable problems; empty means valid.
     */
    public static List<String> validate(long blueThreshold, long redThreshold, double unwitnessedFactor,
                                        int maxCaptivityRealMinutes, List<? extends String> protectedEntities,
                                        List<? extends String> responderEntities) {
        List<String> problems = new ArrayList<>();

        if (blueThreshold <= redThreshold) {
            problems.add("Band thresholds invalid: karmaBlueThreshold (" + blueThreshold
                    + ") must be greater than karmaRedThreshold (" + redThreshold + ").");
        }
        if (unwitnessedFactor < 0.0 || unwitnessedFactor > 1.0) {
            problems.add("unwitnessedKarmaFactor (" + unwitnessedFactor + ") must be between 0.0 and 1.0.");
        }
        if (maxCaptivityRealMinutes <= 0) {
            problems.add("maxCaptivityRealMinutes (" + maxCaptivityRealMinutes + ") must be positive.");
        }

        checkEntityIds("protectedEntities", protectedEntities, problems);
        checkEntityIds("responderEntities", responderEntities, problems);
        return problems;
    }

    private static void checkEntityIds(String listName, List<? extends String> ids, List<String> problems) {
        checkIds(listName, ids, "entity", true, problems);
    }

    /**
     * Parse-checks one id list. {@code noun} names what a plain entry is ("entity", "item"), so the same
     * check can report an item list without calling a mistyped item id an entity. Entity lists accept a
     * wildcard pattern and resolve it at use time; item lists do not, because nothing resolves one.
     */
    private static void checkIds(String listName, List<? extends String> ids, String noun,
                                 boolean allowWildcards, List<String> problems) {
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                problems.add(listName + " contains a blank entry.");
                continue;
            }
            if (id.indexOf('*') >= 0) {
                if (allowWildcards) {
                    continue; // wildcard pattern — accepted, resolved at use time
                }
                problems.add(listName + " uses a wildcard ('" + id + "'), which is not supported here; "
                        + "list each " + noun + " id or use a #tag.");
                continue;
            }
            String toParse = id.startsWith("#") ? id.substring(1) : id;
            if (ResourceLocation.tryParse(toParse) == null) {
                problems.add(listName + " has an invalid " + (id.startsWith("#") ? "tag" : noun)
                        + " id: '" + id + "'.");
            }
        }
    }

    /**
     * The weapon-classification lists, as a pure function of the values.
     *
     * <p>A separate method for the reason {@link #validateJail} is: the existing signatures have tests
     * written against them. Nothing here is fatal — a malformed entry is dropped by the classifier and
     * reported, because one bad line must not take a server owner's whole weapon list down with it.
     */
    public static List<String> validateWeapons(List<? extends String> whitelist, List<? extends String> blacklist,
                                               List<? extends String> gunKeywords, List<? extends String> weaponMods,
                                               double minAttackDamage) {
        List<String> problems = new ArrayList<>();
        checkIds("weapons.whitelist", whitelist, "item", false, problems);
        checkIds("weapons.blacklist", blacklist, "item", false, problems);

        for (String entry : whitelist) {
            if (entry != null && !entry.isBlank() && blacklist.contains(entry)) {
                problems.add("weapons.whitelist and weapons.blacklist both list '" + entry
                        + "'. The blacklist wins, so the whitelist entry does nothing.");
            }
        }
        for (String keyword : gunKeywords) {
            if (keyword == null || keyword.isBlank()) {
                problems.add("weapons.gunKeywords contains a blank entry, which would match every item.");
            }
        }
        for (String namespace : weaponMods) {
            if (namespace == null || namespace.isBlank()) {
                problems.add("weapons.weaponMods contains a blank entry, which names no mod.");
            } else if (ResourceLocation.tryParse(namespace + ":x") == null) {
                problems.add("weapons.weaponMods has an invalid namespace: '" + namespace + "'.");
            }
        }
        if (minAttackDamage < 0.0) {
            problems.add("weapons.autoDetectMinAttackDamage (" + minAttackDamage + ") cannot be negative.");
        }
        return problems;
    }

    /**
     * Pure validation of the observation, reaction and enforcement blocks added in 0.4.0.
     *
     * <p>Separate again for the same reason {@link #validateIntegrations} is: the existing signature
     * is what the existing tests call. These are the combinations that produce a configuration which
     * parses cleanly and then quietly does nothing — the state the whole §22.3 audit exists to end.
     */
    public static List<String> validateBehaviour(boolean observations, boolean reactions, boolean dialogue,
                                                 int sightRadius, int hearingRadius, int reportRadius,
                                                 boolean bail, int bailCostPerMinute,
                                                 boolean npcCrime, boolean rescue, boolean kidnappingNpc) {
        List<String> problems = new ArrayList<>();

        if (reactions && !observations) {
            problems.add("enableVillagerReactions is on but enableObservations is off. Reactions are started "
                    + "by observations, so no villager will ever react to anything.");
        }
        if (hearingRadius > 0 && hearingRadius < sightRadius) {
            problems.add("hearingWitnessRadius (" + hearingRadius + ") is smaller than witnessRadius ("
                    + sightRadius + "). Anyone close enough to hear it can already see it, so the hearing "
                    + "witness role can never be produced.");
        }
        if (reportRadius < sightRadius) {
            problems.add("reportRadius (" + reportRadius + ") is smaller than witnessRadius (" + sightRadius
                    + "). A witness will often be unable to reach any guard, so reports will rarely be filed.");
        }
        if (bail && bailCostPerMinute <= 0) {
            problems.add("enableBail is on with bailCostPerMinute 0, so every sentence can be ended for free.");
        }
        if (npcCrime) {
            problems.add("enableNpcCrime is on, but NPC-committed crime is not implemented in this release. "
                    + "The setting is a declared seam and turning it on changes nothing.");
        }
        if (rescue && !kidnappingNpc) {
            problems.add("enableRescue is on but enableKidnappingNpc is off, so there will rarely be an "
                    + "NPC captive to rescue. This is legal, only likely unintended.");
        }
        if (!dialogue) {
            problems.add("enableDialogue is off: villagers will act but never say anything, which reads as "
                    + "missing content rather than as a setting.");
        }
        return problems;
    }

    /**
     * Pure validation of the {@code [integrations]} block.
     *
     * <p>Separate from {@link #validate} rather than folded into it, because that method's signature
     * is what the existing tests call and widening it would make an additive change look like a
     * breaking one. The checks here are the ones that turn a plausible-looking config into a queue
     * that never drains or a status string nothing recognises.
     */
    /**
     * The jail and escort settings, as a pure function of the values.
     *
     * <p>A separate method rather than more parameters on {@link #validateBehaviour}, matching the
     * precedent {@code validateIntegrations} set: the existing signatures have tests written against
     * them, and widening one to add a check is how those tests start being rewritten for reasons that
     * have nothing to do with what they assert.
     */
    public static List<String> validateJail(boolean buildHoldingCell, boolean fallbackEnabled,
                                            double assignedMaxDistance, int escortTimeoutTicks,
                                            double leashBlocks, double tetherBlocks) {
        List<String> problems = new ArrayList<>();
        if (!buildHoldingCell && !fallbackEnabled) {
            problems.add("buildHoldingCell is off and jailFallbackEnabled is off: every arrest will be "
                    + "refused unless an operator has run /crime assignjail nearby. Surrendering will "
                    + "reduce Heat, the guards will stand down, and nobody will ever be jailed.");
        }
        if (leashBlocks >= tetherBlocks) {
            problems.add("escortLeashBlocks (" + leashBlocks + ") is not smaller than escortTetherBlocks ("
                    + tetherBlocks + "): the escort would be abandoned before the lead ever pulled, so an "
                    + "arrested player could simply walk away.");
        }
        if (escortTimeoutTicks <= 0 && assignedMaxDistance > 0.0) {
            problems.add("arrestEscortTimeoutTicks is 0, so every arrest completes instantly by teleport "
                    + "and no guard ever walks a prisoner anywhere. That is a supported setting, but it "
                    + "makes jailAssignedMaxDistance the only thing deciding where they land.");
        }
        return problems;
    }

    /**
     * The guard-population settings.
     *
     * <p>The overlap with MCA is documented rather than flagged: running both is legitimate, and the
     * only genuinely broken configurations are a target that can never act and a cooldown that is not
     * one.
     */
    public static List<String> validateGuardPopulation(boolean enabled, double ratio, int minimum,
                                                       int maxPerPass, int scanInterval, int cooldown) {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        if (ratio <= 0.0 && minimum <= 0) {
            problems.add("manageGuardPopulation is on but guardPopulationRatio is 0 and "
                    + "guardPopulationMinimum is 0, so the target is always zero and no village will "
                    + "ever gain a guard.");
        }
        if (maxPerPass <= 0) {
            problems.add("guardPopulationMaxPerPass must be at least 1, or no pass can convert anybody.");
        }
        if (cooldown < scanInterval) {
            problems.add("guardPopulationCooldownTicks (" + cooldown + ") is shorter than "
                    + "guardPopulationScanIntervalTicks (" + scanInterval + "), so the per-village "
                    + "cooldown never actually holds a village back and every pass re-examines it.");
        }
        return problems;
    }

    /**
     * The guard-intervention block, as a pure function of the values.
     *
     * <p>Ranges are enforced by {@code defineInRange}; what is worth reporting is the pair that parses
     * and then cannot mean what it says -- a response radius a guard may never chase across, and an
     * orphan timeout shorter than the escort timeout that would produce it.
     */
    public static List<String> validateGuardIntervention(double responseRadius, int pursuitTimeoutTicks,
                                                         double aggroRadius, boolean returnStolenGoods,
                                                         double stolenGoodsReturnRadius,
                                                         int escortTimeoutTicks, int orphanTicks) {
        List<String> problems = new ArrayList<>();
        if (responseRadius > aggroRadius) {
            problems.add("guardThiefResponseRadius (" + responseRadius + ") is larger than guardAggroRadius ("
                    + aggroRadius + "), so a guard notices muggings it is immediately told to stop chasing.");
        }
        if (pursuitTimeoutTicks < 40) {
            problems.add("guardThiefPursuitTimeoutTicks (" + pursuitTimeoutTicks
                    + ") is too short for a guard to cross guardAggroRadius; no pursuit would ever reach.");
        }
        if (returnStolenGoods && stolenGoodsReturnRadius <= 0.0D) {
            problems.add("returnStolenGoodsOnArrest is on but stolenGoodsReturnRadius is 0, so no victim "
                    + "is ever near enough and nothing is ever returned.");
        }
        if (escortTimeoutTicks > 0 && orphanTicks < escortTimeoutTicks) {
            problems.add("npcEscortOrphanTicks (" + orphanTicks + ") is shorter than arrestEscortTimeoutTicks ("
                    + escortTimeoutTicks + "), so an escort is jailed in place before it has run out of time.");
        }
        return problems;
    }

    /**
     * The currency selection, as a pure function of the value.
     *
     * <p>A separate method for the reason every other block here is: {@code validateIntegrations} has
     * tests written against its signature, and widening it to add a check is how those tests start
     * being rewritten for reasons unrelated to what they assert. Only the <em>shape</em> is checked --
     * whether an id is actually registered depends on which mods loaded, and {@code Currencies} already
     * warns once and falls back rather than failing.
     */
    public static List<String> validateCurrency(String currencyId) {
        List<String> problems = new ArrayList<>();
        if (currencyId == null || currencyId.isBlank()) {
            problems.add("integrations.currencyId is blank; it must name a currency, e.g. 'mcacrime:emerald'.");
        } else if (ResourceLocation.tryParse(currencyId.trim()) == null) {
            problems.add("integrations.currencyId is not a valid id: '" + currencyId + "'.");
        }
        return problems;
    }

    /**
     * The criminal-job block, as a pure function of the values.
     *
     * <p>The ranges themselves are already enforced by {@code defineInRange}. What is worth reporting
     * is the combination that parses and then means something the operator did not intend: a fence
     * minimum no village will ever reach, and both occupations switched off while the sweep still runs.
     */
    public static List<String> validateCriminalJobs(boolean enableThieves, boolean enableFences,
                                                    double villageThiefChance, double villageFenceChance,
                                                    double wildThiefChance, int minVillagePopulationForFence,
                                                    int cooldownDays, int scanIntervalTicks,
                                                    int staleRecordGraceDays) {
        List<String> problems = new ArrayList<>();
        problems.addAll(chance("villageThiefChance", villageThiefChance));
        problems.addAll(chance("villageFenceChance", villageFenceChance));
        problems.addAll(chance("wildThiefChance", wildThiefChance));
        if (minVillagePopulationForFence < 1) {
            problems.add("criminalJobs.minVillagePopulationForFence (" + minVillagePopulationForFence
                    + ") must be at least 1.");
        }
        if (cooldownDays < 0) {
            problems.add("criminalJobs.criminalAssignmentCooldownDays (" + cooldownDays
                    + ") cannot be negative.");
        }
        if (scanIntervalTicks < 1) {
            problems.add("criminalJobs.assignmentScanIntervalTicks (" + scanIntervalTicks
                    + ") must be at least 1.");
        }
        if (staleRecordGraceDays < 1) {
            problems.add("criminalJobs.staleRecordGraceDays (" + staleRecordGraceDays
                    + ") must be at least 1, or criminal records are dropped the day they are written.");
        }
        if (enableFences && villageFenceChance > 0.0D && minVillagePopulationForFence > 200) {
            problems.add("criminalJobs.minVillagePopulationForFence (" + minVillagePopulationForFence
                    + ") is larger than any MCA village, so no fence can ever be assigned.");
        }
        if (!enableThieves && !enableFences) {
            problems.add("criminalJobs.enableThieves and enableFences are both off, so no villager will "
                    + "ever take a criminal job; the mugging and fencing features are inert.");
        }
        return problems;
    }

    /**
     * The {@code criminalJobs.thief} block and the two NPC-mug intervals beside it.
     *
     * <p>Ranges are already enforced by {@code defineInRange}; what is worth reporting is the pair
     * that parses and then means something nobody intended — a hard-abort radius wider than the
     * radius guards are looked for in at all, a threshold no risk score can reach, and a search
     * radius further than a mugging can be held at.
     */
    public static List<String> validateThief(int mugDurationTicks, int mugCooldownTicks, int scanIntervalTicks,
                                             double targetSearchRadius, double guardAvoidRadius,
                                             double guardHardAbortRadius, double guardRiskAbortThreshold,
                                             int hudUpdateIntervalTicks, int weaponCheckIntervalTicks) {
        List<String> problems = new ArrayList<>();
        if (mugDurationTicks < 1) {
            problems.add("criminalJobs.thief.mugDurationTicks (" + mugDurationTicks
                    + ") must be at least 1, or a mugging would complete before the victim saw it.");
        }
        if (mugCooldownTicks < 0) {
            problems.add("criminalJobs.thief.mugCooldownTicks (" + mugCooldownTicks + ") cannot be negative.");
        }
        if (scanIntervalTicks < 1) {
            problems.add("criminalJobs.thief.scanIntervalTicks (" + scanIntervalTicks + ") must be at least 1.");
        }
        if (guardRiskAbortThreshold < 0.0D || guardRiskAbortThreshold > 1.0D) {
            problems.add("criminalJobs.thief.guardRiskAbortThreshold (" + guardRiskAbortThreshold
                    + ") must be between 0.0 and 1.0.");
        }
        if (guardHardAbortRadius > guardAvoidRadius) {
            problems.add("criminalJobs.thief.guardHardAbortRadius (" + guardHardAbortRadius
                    + ") is larger than guardAvoidRadius (" + guardAvoidRadius
                    + "), so the guards it describes are never looked for and it can never fire.");
        }
        if (guardRiskAbortThreshold <= 0.0D && guardAvoidRadius > 0.0D) {
            problems.add("criminalJobs.thief.guardRiskAbortThreshold is 0, so any guard at all refuses "
                    + "every target and no thief will ever mug anybody.");
        }
        if (targetSearchRadius > 0.0D && targetSearchRadius < 6.0D) {
            problems.add("criminalJobs.thief.targetSearchRadius (" + targetSearchRadius
                    + ") is smaller than the six blocks a mugging can be held at; thieves would only "
                    + "ever pick victims already standing on top of them.");
        }
        if (hudUpdateIntervalTicks < 1) {
            problems.add("npccrime.npcMugHudUpdateIntervalTicks (" + hudUpdateIntervalTicks
                    + ") must be at least 1.");
        }
        if (weaponCheckIntervalTicks < 1) {
            problems.add("npccrime.npcMugWeaponCheckIntervalTicks (" + weaponCheckIntervalTicks
                    + ") must be at least 1, or drawing a weapon would never stop a mugging.");
        }
        return problems;
    }

    private static List<String> fraction(String key, double value) {
        return value < 0.0D || value > 1.0D
                ? List.of("criminalJobs." + key + " (" + value + ") must be between 0.0 and 1.0.")
                : List.of();
    }

    private static List<String> chance(String key, double value) {
        return value < 0.0D || value > 1.0D
                ? List.of("criminalJobs." + key + " (" + value + ") must be between 0.0 and 1.0.")
                : List.of();
    }

    /**
     * The threat-compliance block, as a pure function of the values.
     *
     * <p>A separate method for the reason every other block here is. The ranges are already enforced by
     * {@code defineInRange}, so what is worth reporting is the combination that parses cleanly and then
     * quietly does nothing: a freeze nobody can be held by, and a slowdown that does not slow.
     */
    /**
     * The {@code criminalJobs.thief} theft block.
     *
     * <p>Ranges are enforced by {@code defineInRange}; what is worth reporting is the pair that
     * parses and then cannot mean what it says — a minimum above the maximum, and the combination
     * that leaves a thief able to take nothing at all.
     */
    public static List<String> validateTheft(int minCurrencySteal, int maxCurrencySteal,
                                             boolean stealAllIfBelowMinimum, boolean protectHotbar,
                                             boolean protectArmor, boolean protectOffhand,
                                             int stolenGoodsPersistenceDays) {
        List<String> problems = new ArrayList<>();
        if (minCurrencySteal < 0) {
            problems.add("criminalJobs.thief.minCurrencySteal (" + minCurrencySteal + ") cannot be negative.");
        }
        if (maxCurrencySteal < 0) {
            problems.add("criminalJobs.thief.maxCurrencySteal (" + maxCurrencySteal + ") cannot be negative.");
        }
        if (minCurrencySteal > maxCurrencySteal) {
            problems.add("criminalJobs.thief.minCurrencySteal (" + minCurrencySteal
                    + ") is larger than maxCurrencySteal (" + maxCurrencySteal
                    + "); the minimum is clamped down to the maximum and never applies.");
        }
        if (maxCurrencySteal == 0 && protectHotbar && protectArmor && protectOffhand) {
            // Slots 9-35 are still eligible, so this is not fatal -- but a pack that also empties the
            // main inventory of every player has built a thief who can only ever fail.
            problems.add("criminalJobs.thief.maxCurrencySteal is 0, so muggings can only ever take an "
                    + "ordinary inventory item; a victim carrying nothing in slots 9-35 loses nothing.");
        }
        if (!stealAllIfBelowMinimum && minCurrencySteal > maxCurrencySteal) {
            problems.add("criminalJobs.thief.stealAllIfBelowMinimum is off and the minimum exceeds the "
                    + "maximum, so currency theft can never happen.");
        }
        if (stolenGoodsPersistenceDays < 0 || stolenGoodsPersistenceDays > 365) {
            problems.add("criminalJobs.thief.stolenGoodsPersistenceDays (" + stolenGoodsPersistenceDays
                    + ") must be between 0 and 365.");
        }
        return problems;
    }

    /**
     * The {@code criminalJobs.fence} block.
     *
     * <p>Ranges are enforced by {@code defineInRange}; what is worth reporting is the pair that
     * parses and then cannot mean what it says — a floor above the ceiling, which would clamp every
     * price to a single value, and a buy ratio of 1 or more, which would let a player sell an item
     * back for what they paid and print money out of one fence.
     */
    public static List<String> validateFence(double maxKarmaDiscount, double maxHeatMarkup,
                                             double wantedMarkup, double minimumPriceMultiplier,
                                             double maximumPriceMultiplier, double buyPriceRatio,
                                             int defaultBasePrice, int offerCount, int offerMaxUses,
                                             int restockIntervalDays) {
        List<String> problems = new ArrayList<>();
        problems.addAll(fraction("fence.maxKarmaDiscount", maxKarmaDiscount));
        problems.addAll(fraction("fence.maxHeatMarkup", maxHeatMarkup));
        problems.addAll(fraction("fence.wantedMarkup", wantedMarkup));
        if (minimumPriceMultiplier <= 0.0D) {
            problems.add("criminalJobs.fence.minimumPriceMultiplier (" + minimumPriceMultiplier
                    + ") must be greater than 0, or a fence would give its stock away.");
        }
        if (minimumPriceMultiplier >= maximumPriceMultiplier) {
            problems.add("criminalJobs.fence.minimumPriceMultiplier (" + minimumPriceMultiplier
                    + ") must be below maximumPriceMultiplier (" + maximumPriceMultiplier
                    + "), or every price is clamped to one value and Karma and Heat stop mattering.");
        }
        if (buyPriceRatio > minimumPriceMultiplier) {
            // Warned rather than fatal, because FencePolicy clamps it to this bound on the way in and
            // the game is playable with the clamped value. Reported every reload, and the clamp is
            // idempotent, so a second reload of the same file says the same thing and changes nothing.
            problems.add("criminalJobs.fence.buyPriceRatio (" + buyPriceRatio
                    + ") is above minimumPriceMultiplier (" + minimumPriceMultiplier
                    + "), so a fence could pay more for an item than the least it ever charges for one;"
                    + " it has been clamped to " + minimumPriceMultiplier + " for pricing.");
        }
        if (buyPriceRatio <= 0.0D || buyPriceRatio >= 1.0D) {
            problems.add("criminalJobs.fence.buyPriceRatio (" + buyPriceRatio
                    + ") must be between 0.0 and 1.0 exclusive; at 1.0 or above a fence pays what it "
                    + "charges and buying then re-selling is free money.");
        }
        if (defaultBasePrice < 1) {
            problems.add("criminalJobs.fence.defaultBasePrice (" + defaultBasePrice
                    + ") must be at least 1.");
        }
        if (offerCount < 1) {
            problems.add("criminalJobs.fence.offerCount (" + offerCount
                    + ") must be at least 1, or a fence opens an empty screen.");
        }
        if (offerMaxUses < 1) {
            problems.add("criminalJobs.fence.offerMaxUses (" + offerMaxUses
                    + ") must be at least 1, or no trade a fence offers could ever be taken.");
        }
        if (restockIntervalDays < 0) {
            problems.add("criminalJobs.fence.restockIntervalDays (" + restockIntervalDays
                    + ") cannot be negative.");
        }
        return problems;
    }

    /**
     * The bounty block, as a pure function of the values.
     *
     * <p>A separate method for the reason every other block here is. {@code defineInRange} already
     * guards each key on its own; what is worth reporting is the pair that parses cleanly and then
     * quietly makes bounty hunting impossible or free — a floor above the ceiling, a price of nothing,
     * or both payout routes switched off while the system is still nominally enabled.
     */
    public static List<String> validateBounty(boolean enabled, int baseBounty, int minBounty, int maxBounty,
                                              double severityRewardScale, double fineRewardShare,
                                              int repeatOffenderBonus, boolean payForKills,
                                              boolean payForAliveCapture, double killMultiplier,
                                              double aliveCaptureMultiplier, int karmaReward,
                                              int claimRetentionDays, double deliveryRadius) {
        List<String> problems = new ArrayList<>();
        if (minBounty > maxBounty) {
            problems.add("bounty.minBounty (" + minBounty + ") cannot exceed bounty.maxBounty (" + maxBounty
                    + "); every price would clamp to the ceiling and the whole formula would stop mattering.");
        }
        if (severityRewardScale < 0.0D || severityRewardScale > 100.0D) {
            problems.add("bounty.severityRewardScale (" + severityRewardScale + ") must be between 0.0 and 100.0.");
        }
        if (fineRewardShare < 0.0D || fineRewardShare > 1.0D) {
            problems.add("bounty.fineRewardShare (" + fineRewardShare + ") must be between 0.0 and 1.0.");
        }
        if (repeatOffenderBonus < 0) {
            problems.add("bounty.repeatOffenderBonus (" + repeatOffenderBonus + ") cannot be negative.");
        }
        if (killMultiplier < 0.0D || killMultiplier > 10.0D) {
            problems.add("bounty.killMultiplier (" + killMultiplier + ") must be between 0.0 and 10.0.");
        }
        if (aliveCaptureMultiplier < 0.0D || aliveCaptureMultiplier > 10.0D) {
            problems.add("bounty.aliveCaptureMultiplier (" + aliveCaptureMultiplier
                    + ") must be between 0.0 and 10.0.");
        }
        if (karmaReward < 0 || karmaReward > 50) {
            problems.add("bounty.karmaReward (" + karmaReward + ") must be between 0 and 50.");
        }
        if (claimRetentionDays < 1) {
            problems.add("bounty.claimRetentionDays (" + claimRetentionDays
                    + ") must be at least 1; a claim forgotten the same day it is paid can be paid again.");
        }
        if (deliveryRadius < 1.0D || deliveryRadius > 16.0D) {
            problems.add("bounty.deliveryRadius (" + deliveryRadius + ") must be between 1.0 and 16.0.");
        }
        if (enabled && !payForKills && !payForAliveCapture) {
            problems.add("bounty.enabled is on with both payForKills and payForAliveCapture off, so no "
                    + "bounty can ever be collected by any route.");
        }
        if (enabled && maxBounty == 0) {
            problems.add("bounty.maxBounty is 0, so every bounty pays nothing however bad the outlaw is.");
        }
        if (enabled && baseBounty == 0 && severityRewardScale == 0.0D && fineRewardShare == 0.0D
                && repeatOffenderBonus == 0) {
            problems.add("Every term of the bounty formula is 0, so every price collapses to minBounty "
                    + "and a hunter is paid the same for a pickpocket as for a murderer.");
        }
        return problems;
    }

    public static List<String> validateCompliance(double speedMultiplier, boolean freezeComplyingVictims,
                                                  boolean armedVillagersCanResist, double resistThreshold,
                                                  double helpThreshold) {
        List<String> problems = new ArrayList<>();
        if (speedMultiplier <= 0.0 || speedMultiplier > 1.0) {
            problems.add("reactions.civilianCrimeReactionSpeedMultiplier (" + speedMultiplier
                    + ") must be above 0.0 and at most 1.0.");
        }
        if (resistThreshold < 0.0 || resistThreshold > 1.0) {
            problems.add("reactions.complianceResistThreshold (" + resistThreshold
                    + ") must be between 0.0 and 1.0.");
        }
        if (helpThreshold < 0.0 || helpThreshold > 1.0) {
            problems.add("reactions.complianceHelpThreshold (" + helpThreshold
                    + ") must be between 0.0 and 1.0.");
        }
        if (resistThreshold <= 0.0 && !armedVillagersCanResist) {
            problems.add("reactions.complianceResistThreshold is 0 with armedVillagersCanResist off, so "
                    + "every villager resists every threat and nobody can ever be mugged.");
        }
        if (!freezeComplyingVictims && helpThreshold >= 1.0 && resistThreshold >= 1.0) {
            problems.add("reactions.freezeComplyingVictims is off and both compliance thresholds are 1.0, "
                    + "so every threatened villager can only ever run.");
        }
        return problems;
    }

    public static List<String> validateIntegrations(int pumpIntervalTicks, int pumpBudgetPerTick,
                                                    int maxDeliveryAttempts, int retryBaseDelayTicks,
                                                    int retryMaxDelayTicks, int dedupeRetentionTicks,
                                                    String fineResolutionStatus,
                                                    String servedResolutionStatus) {
        List<String> problems = new ArrayList<>();
        if (pumpIntervalTicks <= 0) {
            problems.add("integrations.pumpIntervalTicks must be positive; queued cross-mod writes "
                    + "would never be delivered.");
        }
        if (pumpBudgetPerTick <= 0) {
            problems.add("integrations.pumpBudgetPerTick must be positive; queued cross-mod writes "
                    + "would never be delivered.");
        }
        if (maxDeliveryAttempts <= 0) {
            problems.add("integrations.maxDeliveryAttempts must be at least 1.");
        }
        if (retryBaseDelayTicks > retryMaxDelayTicks) {
            problems.add("integrations.retryBaseDelayTicks (" + retryBaseDelayTicks
                    + ") is above retryMaxDelayTicks (" + retryMaxDelayTicks
                    + "); the backoff ceiling would sit below its own starting point.");
        }
        if (dedupeRetentionTicks <= 0) {
            problems.add("integrations.dedupeRetentionTicks must be positive; a replayed transaction "
                    + "would be applied a second time.");
        }
        if (!CrimeIncidentMapping.isValidConfiguredStatus(fineResolutionStatus)) {
            problems.add("integrations.reputation.fineResolutionStatus must be 'atoned' or 'apologized', "
                    + "not '" + fineResolutionStatus + "'.");
        }
        if (!CrimeIncidentMapping.isValidConfiguredStatus(servedResolutionStatus)) {
            problems.add("integrations.reputation.servedResolutionStatus must be 'atoned' or 'apologized', "
                    + "not '" + servedResolutionStatus + "'.");
        }
        return problems;
    }

    /**
     * Runtime validation against the live config, plus a best-effort registry-existence check on the
     * entity-id lists. Used by {@code /crime validate} and load-time validation.
     */
    public static List<String> validateCurrentConfig() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        List<String> problems = validate(
                c.karmaBlueThreshold.get(),
                c.karmaRedThreshold.get(),
                c.unwitnessedKarmaFactor.get(),
                c.maxCaptivityRealMinutes.get(),
                c.protectedEntities.get(),
                c.responderEntities.get());

        problems.addAll(validateJail(
                c.buildHoldingCell.get(),
                c.jailFallbackEnabled.get(),
                c.jailAssignedMaxDistance.get(),
                c.arrestEscortTimeoutTicks.get(),
                c.escortLeashBlocks.get(),
                c.escortTetherBlocks.get()));
        problems.addAll(validateGuardPopulation(
                c.manageGuardPopulation.get(),
                c.guardPopulationRatio.get(),
                c.guardPopulationMinimum.get(),
                c.guardPopulationMaxPerPass.get(),
                c.guardPopulationScanIntervalTicks.get(),
                c.guardPopulationCooldownTicks.get()));
        problems.addAll(validateIntegrations(
                c.pumpIntervalTicks.get(),
                c.pumpBudgetPerTick.get(),
                c.maxDeliveryAttempts.get(),
                c.retryBaseDelayTicks.get(),
                c.retryMaxDelayTicks.get(),
                c.dedupeRetentionTicks.get(),
                c.fineResolutionStatus.get(),
                c.servedResolutionStatus.get()));
        problems.addAll(validateGuardIntervention(
                c.guardThiefResponseRadius.get(),
                c.guardThiefPursuitTimeoutTicks.get(),
                c.guardAggroRadius.get(),
                c.returnStolenGoodsOnArrest.get(),
                c.stolenGoodsReturnRadius.get(),
                c.arrestEscortTimeoutTicks.get(),
                c.npcEscortOrphanTicks.get()));
        problems.addAll(validateCurrency(c.currencyId.get()));
        problems.addAll(validateCriminalJobs(
                c.enableThieves.get(),
                c.enableFences.get(),
                c.villageThiefChance.get(),
                c.villageFenceChance.get(),
                c.wildThiefChance.get(),
                c.minVillagePopulationForFence.get(),
                c.criminalAssignmentCooldownDays.get(),
                c.assignmentScanIntervalTicks.get(),
                c.staleRecordGraceDays.get()));
        problems.addAll(validateThief(
                c.thiefMugDurationTicks.get(),
                c.thiefMugCooldownTicks.get(),
                c.thiefScanIntervalTicks.get(),
                c.thiefTargetSearchRadius.get(),
                c.thiefGuardAvoidRadius.get(),
                c.thiefGuardHardAbortRadius.get(),
                c.thiefGuardRiskAbortThreshold.get(),
                c.npcMugHudUpdateIntervalTicks.get(),
                c.npcMugWeaponCheckIntervalTicks.get()));
        problems.addAll(validateTheft(
                c.thiefMinCurrencySteal.get(),
                c.thiefMaxCurrencySteal.get(),
                c.thiefStealAllIfBelowMinimum.get(),
                c.thiefProtectHotbar.get(),
                c.thiefProtectArmor.get(),
                c.thiefProtectOffhand.get(),
                c.thiefStolenGoodsPersistenceDays.get()));
        problems.addAll(validateFence(
                c.fenceMaxKarmaDiscount.get(),
                c.fenceMaxHeatMarkup.get(),
                c.fenceWantedMarkup.get(),
                c.fenceMinimumPriceMultiplier.get(),
                c.fenceMaximumPriceMultiplier.get(),
                c.fenceBuyPriceRatio.get(),
                c.fenceDefaultBasePrice.get(),
                c.fenceOfferCount.get(),
                c.fenceOfferMaxUses.get(),
                c.fenceRestockIntervalDays.get()));
        problems.addAll(validateCompliance(
                c.civilianCrimeReactionSpeedMultiplier.get(),
                c.freezeComplyingVictims.get(),
                c.armedVillagersCanResist.get(),
                c.complianceResistThreshold.get(),
                c.complianceHelpThreshold.get()));

        problems.addAll(validateBounty(
                c.bountyEnabled.get(),
                c.baseBounty.get(),
                c.minBounty.get(),
                c.maxBounty.get(),
                c.severityRewardScale.get(),
                c.fineRewardShare.get(),
                c.repeatOffenderBonus.get(),
                c.payForKills.get(),
                c.payForAliveCapture.get(),
                c.killMultiplier.get(),
                c.aliveCaptureMultiplier.get(),
                c.bountyKarmaReward.get(),
                c.claimRetentionDays.get(),
                c.bountyDeliveryRadius.get()));

        problems.addAll(validateBehaviour(
                c.enableObservations.get(),
                c.enableVillagerReactions.get(),
                c.enableDialogue.get(),
                c.witnessRadius.get(),
                c.hearingWitnessRadius.get(),
                c.reportRadius.get(),
                c.enableBail.get(),
                c.bailCostPerMinute.get(),
                c.enableNpcCrime.get(),
                c.enableRescue.get(),
                c.enableKidnappingNpc.get()));

        problems.addAll(validateWeapons(
                c.weaponWhitelist.get(),
                c.weaponBlacklist.get(),
                c.weaponGunKeywords.get(),
                c.weaponMods.get(),
                c.weaponAutoDetectMinAttackDamage.get()));

        registryCheck("protectedEntities", c.protectedEntities.get(), BuiltInRegistries.ENTITY_TYPE,
                "an entity type", problems);
        registryCheck("responderEntities", c.responderEntities.get(), BuiltInRegistries.ENTITY_TYPE,
                "an entity type", problems);
        registryCheck("weapons.whitelist", c.weaponWhitelist.get(), BuiltInRegistries.ITEM, "an item", problems);
        registryCheck("weapons.blacklist", c.weaponBlacklist.get(), BuiltInRegistries.ITEM, "an item", problems);

        // Jail / fine sanity (spec §6, §7, §12.3).
        if (c.jailableHeatThreshold.get() < c.wantedHeatThreshold.get()) {
            problems.add("jailableHeatThreshold (" + c.jailableHeatThreshold.get()
                    + ") should be at least wantedHeatThreshold (" + c.wantedHeatThreshold.get() + ").");
        }
        if (c.jailFallbackEnabled.get()) {
            if (ResourceLocation.tryParse(c.jailFallbackDim.get()) == null) {
                problems.add("jailFallbackDim is not a valid dimension id: '" + c.jailFallbackDim.get() + "'.");
            }
            if (c.jailFallbackPos.get().size() < 3) {
                problems.add("jailFallbackPos must list 3 coordinates [x, y, z].");
            }
        }

        // Ransom table sanity (spec §8.5, §12.3): catch a table that can never produce a real demand.
        if (c.ransomDemandTtlTicks.get() == 0) {
            problems.add("ransomDemandTtlTicks is 0 — open ransom demands would expire instantly.");
        }
        boolean allTiersZero = c.ransomSpouseMultiplier.get() == 0.0 && c.ransomParentMultiplier.get() == 0.0
                && c.ransomChildMultiplier.get() == 0.0 && c.ransomSiblingMultiplier.get() == 0.0
                && c.ransomRelativeMultiplier.get() == 0.0 && c.ransomVillageMultiplier.get() == 0.0;
        if (c.ransomBaseAmount.get() > 0 && allTiersZero) {
            problems.add("All ransom tier multipliers are 0 — every ransom would be free despite a non-zero base.");
        }
        if (c.muggingPurseInitialMax.get() > c.muggingPurseCapacity.get()) {
            problems.add("muggingPurseInitialMax cannot exceed muggingPurseCapacity.");
        }
        if (c.muggingBaseLoot.get() > c.muggingPurseCapacity.get()) {
            problems.add("muggingBaseLoot exceeds purse capacity; the configured excess can never transfer.");
        }
        if (c.maxUnlawfulCaptivesPerCaptor.get() < 1) {
            problems.add("maxUnlawfulCaptivesPerCaptor must be at least 1.");
        }
        if (c.restraintEscapeChanceLockedCuffs.get() > 0.0D) {
            problems.add("Locked cuffs have a non-zero escape chance but no work duration; use keys/rescue or set the chance to 0.");
        }

        // Surface crime-definition JSON parse errors from the last datapack load (spec §12.3).
        for (String crimeError : CrimeTypeRegistry.lastErrors()) {
            problems.add("Crime JSON: " + crimeError);
        }
        return problems;
    }

    private static void registryCheck(String listName, List<? extends String> ids,
                                      Registry<?> registry, String noun, List<String> problems) {
        for (String id : ids) {
            if (id == null || id.isBlank() || id.startsWith("#") || id.indexOf('*') >= 0) {
                continue; // blanks/tags/wildcards handled (or skipped) by the parse pass
            }
            try {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (rl != null && !registry.containsKey(rl)) {
                    problems.add(listName + " references " + noun + " that is not registered: '" + id + "'.");
                }
            } catch (Throwable ignored) {
                // Registries unavailable (e.g. very early load) — skip the existence check silently.
            }
        }
    }
}
