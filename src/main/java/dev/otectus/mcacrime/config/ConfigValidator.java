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
