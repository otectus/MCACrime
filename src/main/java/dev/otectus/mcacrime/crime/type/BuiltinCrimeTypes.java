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
