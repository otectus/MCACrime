package dev.otectus.mcacrime.crime.type;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hardcoded fallback crime definitions so detection keeps working even if the datapack JSON is missing,
 * deleted, or fails to parse (spec §0 rule 4, fail-safe). The shipped {@code crimes/*.json} mirror these
 * values, so out-of-the-box behavior is identical; the builtins are the safety net.
 */
public final class BuiltinCrimeTypes {

    private static final Map<ResourceLocation, CrimeType> BUILTINS = new LinkedHashMap<>();

    static {
        put(new CrimeType(CrimeIds.HARM_VILLAGER, -10L, 15L, 1.0, "villager"));
        put(new CrimeType(CrimeIds.KILL_VILLAGER, -50L, 40L, 1.0, "villager"));
        put(new CrimeType(CrimeIds.ASSAULT_GUARD, -15L, 25L, 1.0, "guard"));
        put(new CrimeType(CrimeIds.JAILBREAK, -20L, 30L, 1.0, ""));
        put(new CrimeType(CrimeIds.KIDNAP, -40L, 35L, 1.0, "villager"));
        put(new CrimeType(CrimeIds.THEFT, -8L, 12L, 1.0, "villager"));
        put(new CrimeType(CrimeIds.MUGGING_MURDER, -70L, 55L, 1.0, "villager"));
        put(new CrimeType(CrimeIds.EXTORTION, -12L, 10L, 1.0, "villager"));
        // NPC offenders (0.5.1). Karma and Heat are zero deliberately: both are player-side scores
        // kept in a player capability, and a villager has neither. The record exists so the crime is
        // reportable, chargeable and visible in the ledger, not to move a number nobody owns.
        put(new CrimeType(CrimeIds.MUGGING, 0L, 0L, 1.0, "player"));
        put(new CrimeType(CrimeIds.ATTEMPTED_MUGGING, 0L, 0L, 1.0, "player"));
        // Player-on-player, and only ever reached with pvpCountsAsCrime on. Weighted close to the
        // villager equivalents so a server that enables it does not get a second, harsher legal system.
        put(new CrimeType(CrimeIds.ASSAULT_PLAYER, -10L, 15L, 1.0, "player"));
        put(new CrimeType(CrimeIds.MURDER_PLAYER, -50L, 40L, 1.0, "player"));
    }

    private BuiltinCrimeTypes() {
    }

    private static void put(CrimeType type) {
        BUILTINS.put(type.id(), type);
    }

    public static Optional<CrimeType> get(ResourceLocation id) {
        return Optional.ofNullable(BUILTINS.get(id));
    }
}
