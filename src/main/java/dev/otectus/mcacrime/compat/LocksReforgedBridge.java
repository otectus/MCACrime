package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraftforge.fml.ModList;

/**
 * The optional-classloading seam for Locks Reforged, built to the same discipline as
 * {@link ReputationBridge}.
 *
 * <p>Fence stock uses registry ids; the cuff adapter extends Locks' native menu. Both are reached
 * by name only after a mod-presence check so the native menu is never resolved without Locks.
 *
 * <p>The fence-stock setting does not disable cuff lockpicking. An unavailable cuff adapter reports
 * a failure and never substitutes a timed escape; an absent mod retains the configured timed behavior.
 */
public final class LocksReforgedBridge {

    /** The Locks Reforged mod id, as declared in its own mods.toml. */
    private static final String MOD_ID = "locks";

    private static volatile boolean initialised;
    private static volatile String status = "not initialised";

    private LocksReforgedBridge() {
    }

    /** Whether Locks Reforged is present at all, for the fence-stock decision below. */
    public static boolean installed() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
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

    /**
     * Whether Locks Reforged already owns a lock on {@code target} (§3.16).
     *
     * <p>Answered through the isolated adapter, by name, and only when the mod is actually installed —
     * so a server without it never resolves a Locks class and never pays for the question. An absent
     * mod owns nothing, which is what makes {@code locks.foreignLockPolicy = REFUSE} a no-op on the
     * ordinary single-mod install.
     */
    public static boolean ownsLock(@javax.annotation.Nullable net.minecraft.world.level.Level level,
                                   @javax.annotation.Nullable dev.otectus.mcacrime.locks.LockTarget target) {
        if (level == null || target == null || target.kind() != dev.otectus.mcacrime.locks.LockTarget.Kind.BLOCK
                || target.pos() == null || !installed()) {
            return false;
        }
        try {
            java.lang.reflect.Method probe = ownsLockMethod();
            return probe != null && Boolean.TRUE.equals(probe.invoke(null, level, target.pos()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile java.lang.reflect.Method ownsLockMethod;

    @javax.annotation.Nullable
    private static java.lang.reflect.Method ownsLockMethod() throws Exception {
        java.lang.reflect.Method cached = ownsLockMethod;
        if (cached == null) {
            cached = Class.forName("dev.otectus.mcacrime.compat.locksreforged.LocksReforgedCompat")
                    .getMethod("ownsLock", Object.class, Object.class);
            ownsLockMethod = cached;
        }
        return cached;
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
