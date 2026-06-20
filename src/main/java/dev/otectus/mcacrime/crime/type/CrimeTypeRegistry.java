package dev.otectus.mcacrime.crime.type;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The loaded crime-type table (spec §5.1), swapped atomically by {@link CrimeTypeLoader} on datapack
 * load/reload. Mirrors {@code mca-quests}' QuestRegistry: {@code volatile} immutable snapshots, with the
 * last load's parse errors exposed for {@code /crime validate}.
 *
 * <p>{@link #getOrBuiltin} is the detector's lookup: datapack entry first, then the hardcoded
 * {@link BuiltinCrimeTypes} fallback, then empty (the detector then aborts that event safely).
 */
public final class CrimeTypeRegistry {

    private static volatile Map<ResourceLocation, CrimeType> types = Map.of();
    private static volatile List<String> lastErrors = List.of();

    private CrimeTypeRegistry() {
    }

    static void replaceAll(Map<ResourceLocation, CrimeType> loaded, List<String> errors) {
        types = Map.copyOf(loaded);
        lastErrors = List.copyOf(errors);
    }

    public static Optional<CrimeType> get(ResourceLocation id) {
        return Optional.ofNullable(types.get(id));
    }

    /** Datapack entry, else the built-in fallback, else empty. */
    public static Optional<CrimeType> getOrBuiltin(ResourceLocation id) {
        CrimeType loaded = types.get(id);
        return loaded != null ? Optional.of(loaded) : BuiltinCrimeTypes.get(id);
    }

    public static Collection<CrimeType> all() {
        return types.values();
    }

    public static int size() {
        return types.size();
    }

    public static List<String> lastErrors() {
        return lastErrors;
    }

    /** No-op; exists only to force class-load (and thus the built-in id constants) during setup. */
    public static void bootstrap() {
    }
}
