package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

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
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                problems.add(listName + " contains a blank entry.");
                continue;
            }
            if (id.indexOf('*') >= 0) {
                continue; // wildcard pattern — accepted, resolved at use time
            }
            String toParse = id.startsWith("#") ? id.substring(1) : id;
            if (ResourceLocation.tryParse(toParse) == null) {
                problems.add(listName + " has an invalid " + (id.startsWith("#") ? "tag" : "entity")
                        + " id: '" + id + "'.");
            }
        }
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

        registryCheck("protectedEntities", c.protectedEntities.get(), problems);
        registryCheck("responderEntities", c.responderEntities.get(), problems);

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

        // Surface crime-definition JSON parse errors from the last datapack load (spec §12.3).
        for (String crimeError : CrimeTypeRegistry.lastErrors()) {
            problems.add("Crime JSON: " + crimeError);
        }
        return problems;
    }

    private static void registryCheck(String listName, List<? extends String> ids, List<String> problems) {
        for (String id : ids) {
            if (id == null || id.isBlank() || id.startsWith("#") || id.indexOf('*') >= 0) {
                continue; // blanks/tags/wildcards handled (or skipped) by the parse pass
            }
            try {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (rl != null && !ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
                    problems.add(listName + " references an entity type that is not registered: '" + id + "'.");
                }
            } catch (Throwable ignored) {
                // Registries unavailable (e.g. very early load) — skip the existence check silently.
            }
        }
    }
}
