package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * The optional-classloading seam for PlayerRevive (0.7.5 M6.2, specification §15.1).
 *
 * <p>{@link ModList#isLoaded} first, then {@code Class.forName} into {@code compat/revive}. With the
 * mod absent this answers "nobody is downed", which is the truthful answer in a game where being
 * downed does not exist.
 */
public final class ReviveCompat {

    private static final String MOD_ID = "playerrevive";
    private static final String ADAPTER = "dev.otectus.mcacrime.compat.revive.PlayerReviveAdapter";

    private static volatile boolean initialised;
    private static volatile String status = "not initialised";
    private static volatile Method downedMethod;

    private ReviveCompat() {
    }

    public static boolean installed() {
        ModList list = ModList.get();
        return list != null && list.isLoaded(MOD_ID);
    }

    /** Probes the adapter. Called once from common setup. */
    public static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        if (!installed()) {
            status = "not installed";
            return;
        }
        try {
            Class<?> adapter = Class.forName(ADAPTER);
            status = String.valueOf(adapter.getMethod("probe").invoke(null));
            downedMethod = adapter.getMethod("downed",
                    Class.forName("net.minecraft.world.entity.LivingEntity"));
            McaCrime.LOGGER.info("MCA: Crime - PlayerRevive: {}", status);
        } catch (Throwable t) {
            status = "adapter unavailable: " + t.getClass().getSimpleName();
            downedMethod = null;
            McaCrime.LOGGER.warn("MCA: Crime - PlayerRevive is installed but its adapter could not "
                    + "start; a downed player reads as an ordinary one. ({})", t.toString());
        }
    }

    /**
     * Whether this subject is down rather than dead.
     *
     * <p>False whenever the mod is absent, the adapter did not bind, or the answer cannot be read —
     * and false is the safe direction in both places this is used: an ordinary vulnerability check,
     * and a refusal to execute.
     */
    public static boolean downed(@Nullable LivingEntity subject) {
        if (subject == null || !installed()) {
            return false;
        }
        Method downed = downedMethod;
        if (downed == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(downed.invoke(null, subject));
        } catch (Throwable t) {
            return false;
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
        downedMethod = null;
    }
}
