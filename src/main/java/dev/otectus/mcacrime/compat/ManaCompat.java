package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The optional-classloading seam for the three supported spell mods (0.7.5 M6.2, specification §15.1).
 *
 * <p>Silence drains a spell resource, and there is no vanilla spell resource to drain. Iron's Spells
 * 'n Spellbooks, Ars Nouveau and Mana and Artifice each keep their own pool behind their own
 * capability, so each is reached the same way every optional integration in this mod is reached:
 * {@link ModList#isLoaded} first, then {@code Class.forName} into
 * {@code compat/mana/ManaDrainAdapters}, which is the only class allowed to name any of them — and
 * which names them as strings, because none of the three is on this mod's compile classpath.
 *
 * <p><b>What this does not do.</b> It does not claim support it cannot prove. Every adapter probes
 * for the exact members it needs and reports {@code unsupported} when the probe fails, so a spell
 * mod that reshaped its pool between releases produces one honest log line rather than a silent
 * no-op that looks like a working integration. Silence still shows on the tooltip and still counts
 * as an enchantment; it simply drains nothing, and says so.
 */
public final class ManaCompat {

    /** The three mod ids, exactly as their own {@code mods.toml} files declare them. */
    public static final List<String> SUPPORTED_MOD_IDS =
            List.of("irons_spellbooks", "ars_nouveau", "mana-and-artifice");

    private static final String ADAPTER = "dev.otectus.mcacrime.compat.mana.ManaDrainAdapters";

    private static volatile boolean initialised;
    private static volatile String status = "not initialised";
    private static volatile Method drainMethod;

    private ManaCompat() {
    }

    /** Whether any supported spell mod is installed at all. */
    public static boolean installed() {
        ModList list = ModList.get();
        if (list == null) {
            return false;
        }
        for (String id : SUPPORTED_MOD_IDS) {
            if (list.isLoaded(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Probes whichever spell mods are present. Called once from common setup, after every mod loaded.
     *
     * <p>Never throws: an adapter that cannot bind is a missing feature, not a failed startup.
     */
    public static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        if (!installed()) {
            status = "no supported spell mod installed";
            return;
        }
        try {
            Class<?> adapter = Class.forName(ADAPTER);
            Object report = adapter.getMethod("probe").invoke(null);
            status = String.valueOf(report);
            drainMethod = adapter.getMethod("drain", Object.class, double.class);
            McaCrime.LOGGER.info("MCA: Crime - Silence mana drain: {}", status);
        } catch (Throwable t) {
            status = "adapter unavailable: " + t.getClass().getSimpleName();
            drainMethod = null;
            McaCrime.LOGGER.warn("MCA: Crime - a spell mod is installed but the Silence drain adapter "
                    + "could not start; Silence will drain nothing. ({})", t.toString());
        }
    }

    /**
     * Drains {@code fraction} of {@code player}'s spell pool, once.
     *
     * @param fraction the share of the maximum pool to remove; clamped into 0..1
     * @return true when a supported pool was actually reduced
     */
    public static boolean drain(@Nullable ServerPlayer player, double fraction) {
        if (player == null || !Double.isFinite(fraction) || fraction <= 0.0D || !installed()) {
            return false;
        }
        Method drain = drainMethod;
        if (drain == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(drain.invoke(null, player, Math.min(1.0D, fraction)));
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
        drainMethod = null;
    }
}
