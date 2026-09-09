package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraftforge.fml.ModList;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

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

    /** Cuff escapes are independent of the optional fence-stock setting. */
    public static boolean installed() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    }

    public static boolean openCuffs(ServerPlayer player, CustodyRecord record) {
        if (!installed()) return false;
        try {
            return (boolean) Class.forName("dev.otectus.mcacrime.compat.locksreforged.CuffLockPickingMenu")
                    .getMethod("open", ServerPlayer.class, CustodyRecord.class).invoke(null, player, record);
        } catch (ReflectiveOperationException | LinkageError e) {
            McaCrime.LOGGER.error("MCA: Crime could not open the Locks Reforged cuff minigame", e);
            player.sendSystemMessage(Component.translatable("mcacrime.captive.escape.lock_unavailable"));
            return false; // Never substitute a timed escape when the installed adapter is unavailable.
        }
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
