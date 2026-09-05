package dev.otectus.mcacrime.util;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The gated diagnostic log behind {@code [debug] debugLogging} (spec §12.3).
 *
 * <p>The key shipped in 0.1.0 and was read by nothing, which made it indistinguishable from a
 * decoration. It is read here and only here, so there is one answer to "is debug logging on" rather
 * than one per caller.
 *
 * <p>The {@code BooleanValue} is cached rather than re-resolved: this is called from tick paths, and
 * resolving a config path per call is exactly the kind of cost that makes a diagnostic aid something
 * an operator is told to leave off. Caching the <em>value</em> would be wrong — a reload must take
 * effect without a restart — so the holder is cached and {@code get()} is still asked each time.
 */
public final class CrimeDebug {

    private static volatile ForgeConfigSpec.BooleanValue flag;

    private CrimeDebug() {
    }

    /** Whether diagnostics are on. False whenever the config is not loaded yet, never an exception. */
    public static boolean enabled() {
        ForgeConfigSpec.BooleanValue value = flag;
        if (value == null) {
            value = McaCrimeConfig.COMMON.debugLogging;
            flag = value;
        }
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return false; // config not loaded (very early load, or a unit test)
        }
    }

    /** A crime-engine diagnostic line. Arguments are only formatted when logging is on. */
    public static void crime(String fmt, Object... args) {
        if (enabled()) {
            McaCrime.LOGGER.info("[crime] " + fmt, args);
        }
    }

    /** A compat/integration diagnostic line. */
    public static void compat(String fmt, Object... args) {
        if (enabled()) {
            McaCrime.LOGGER.info("[compat] " + fmt, args);
        }
    }
}
