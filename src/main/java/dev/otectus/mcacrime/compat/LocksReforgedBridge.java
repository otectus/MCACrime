package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraftforge.fml.ModList;

/**
 * The optional-classloading seam for Locks Reforged, built to the same discipline as
 * {@link ReputationBridge}.
 *
 * <p>The adapter behind this one names no Locks type at all -- it looks its items up by registry id --
 * so strictly speaking the string indirection below is not load-bearing. It is here anyway, because
 * "every optional mod is reached through a bridge" is a rule worth being able to check by looking,
 * and because an adapter that later grew a direct reference would otherwise become a
 * {@code NoClassDefFoundError} on a server that never installed the mod.
 *
 * <p>Absent mod, disabled key and outright failure all end the same way: fences still work, with
 * vanilla contraband only, and the log says which of the three happened.
 */
public final class LocksReforgedBridge {

    /** The Locks Reforged mod id, as declared in its own mods.toml. */
    private static final String MOD_ID = "locks";

    private static volatile boolean initialised;
    private static volatile String status = "not initialised";

    private LocksReforgedBridge() {
    }

    /** Chooses whether the integration runs. Called once from common setup, after every mod has loaded. */
    public static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        if (!McaCrimeConfig.COMMON.locksReforgedFenceTrades.get()) {
            status = "disabled by config";
            return;
        }
        if (!ModList.get().isLoaded(MOD_ID)) {
            status = "not installed";
            return;
        }
        try {
            // Class.forName rather than a direct reference, for the reason in the class note.
            Class.forName("dev.otectus.mcacrime.compat.locksreforged.LocksReforgedCompat")
                    .getMethod("register").invoke(null);
            status = "ready";
            McaCrime.LOGGER.info("MCA: Crime - Locks Reforged detected; fences will stock its locks, picks "
                    + "and keys.");
        } catch (NoClassDefFoundError | NoSuchMethodError e) {
            status = "adapter unavailable";
            McaCrime.LOGGER.error("MCA: Crime - the Locks Reforged fence integration could not load; fences "
                    + "will stock vanilla contraband only. Everything else works normally. ({})", e.toString());
        } catch (Throwable t) {
            status = "failed: " + t.getClass().getSimpleName();
            McaCrime.LOGGER.error("MCA: Crime - Locks Reforged is installed but the fence integration could "
                    + "not start; fences will stock vanilla contraband only.", t);
        }
    }

    /** A short human-readable state for the debug commands. */
    public static String status() {
        return status;
    }

    /** Test and shutdown hook. */
    public static synchronized void reset() {
        initialised = false;
        status = "not initialised";
    }
}
