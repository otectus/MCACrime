package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * Compares a villager's profession against a name, under the server's chosen matching mode.
 *
 * <p>{@code professionMatchingMode} shipped in 0.1.0 with three documented values and no code that read
 * it, while the one place professions were actually compared — the guard check — hard-coded a single
 * strategy. The setting therefore promised configurability over the exact thing it could not affect.
 *
 * <p>The modes matter because profession ids differ across MCA builds and across mods that add their
 * own guards. {@code STRICT} is for a server that knows exactly which ids exist and wants no surprises;
 * {@code NORMALIZED} ignores the namespace, so {@code mca:guard} and {@code somemod:guard} both count;
 * {@code LOOSE} matches a substring, which catches {@code village_guard} and {@code guard_captain} at
 * the cost of also catching anything else with the word in it.
 */
public final class ProfessionMatcher {

    private ProfessionMatcher() {
    }

    /**
     * Whether {@code profession} counts as {@code expected} under the configured mode.
     *
     * @param expected the bare profession name, e.g. {@code "guard"} — never a full id, because the
     *                 whole point of the modes is that the caller does not know the namespace
     */
    public static boolean matches(ResourceLocation profession, String expected) {
        return matches(profession, expected, mode());
    }

    /**
     * The pure form, with the mode supplied. Exists so the three strategies can be tested directly
     * rather than through a config that a unit test has no server to load.
     */
    public static boolean matches(ResourceLocation profession, String expected,
                                  McaCrimeConfig.ProfessionMatchingMode mode) {
        if (profession == null || expected == null || expected.isEmpty() || mode == null) {
            return false;
        }
        String path = profession.getPath().toLowerCase(Locale.ROOT);
        String want = expected.toLowerCase(Locale.ROOT);
        return switch (mode) {
            // Strict compares the whole id, so it only matches MCA's own namespace.
            case STRICT -> profession.toString().toLowerCase(Locale.ROOT).endsWith(":" + want);
            case NORMALIZED -> path.equals(want);
            case LOOSE -> path.contains(want);
        };
    }

    /**
     * Reads the mode without letting a not-yet-loaded config throw. Profession checks run from damage
     * handling, which can fire before config load in a broken setup; the historical behaviour
     * ({@code NORMALIZED}) is the right thing to fall back to.
     */
    private static McaCrimeConfig.ProfessionMatchingMode mode() {
        try {
            return McaCrimeConfig.COMMON.professionMatchingMode.get();
        } catch (IllegalStateException e) {
            return McaCrimeConfig.ProfessionMatchingMode.NORMALIZED;
        }
    }
}
