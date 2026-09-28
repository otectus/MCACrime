package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.RestraintRegistrationApi;
import dev.otectus.mcacrime.frisk.InventoryProvider;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * The optional-classloading seam for foreign inventories (0.7.5 M6.2, specification §15.1).
 *
 * <p>Curios and Cosmetic Armor Reworked keep slots a frisk cannot otherwise see. Both are reached the
 * way every optional integration in this mod is reached: {@link ModList#isLoaded} first, then
 * {@code Class.forName} into {@code compat/inventory}, which is the only class allowed to name them.
 *
 * <p>Registration goes through {@code api/RestraintRegistrationApi} rather than a private back door,
 * deliberately: the mechanism MCA: Crime uses for its own adapters is the mechanism it offers other
 * mods, so the public one cannot quietly rot.
 */
public final class InventoryCompat {

    private static final String ADAPTER = "dev.otectus.mcacrime.compat.inventory.ExternalInventoryAdapters";

    /** The two mod ids, exactly as their own {@code mods.toml} files declare them. */
    public static final List<String> SUPPORTED_MOD_IDS = List.of("curios", "cosmeticarmorreworked");

    private static volatile boolean initialised;
    private static volatile String status = "not initialised";

    private InventoryCompat() {
    }

    /** Whether either is installed at all. */
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

    /** Probes and registers whichever are present. Called once from common setup. */
    @SuppressWarnings("unchecked")
    public static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        if (!installed()) {
            status = "no supported inventory mod installed";
            return;
        }
        try {
            List<String> report = new ArrayList<>();
            Object probed = Class.forName(ADAPTER).getMethod("probe", List.class).invoke(null, report);
            int registered = 0;
            for (InventoryProvider provider : (List<InventoryProvider>) probed) {
                if (RestraintRegistrationApi.registerInventoryProvider(provider).accepted()) {
                    registered++;
                }
            }
            status = report.isEmpty() ? "nothing to bind" : String.join("; ", report);
            McaCrime.LOGGER.info("MCA: Crime - searchable foreign inventories: {} ({} registered)",
                    status, registered);
        } catch (Throwable t) {
            status = "adapter unavailable: " + t.getClass().getSimpleName();
            McaCrime.LOGGER.warn("MCA: Crime - a supported inventory mod is installed but its adapter "
                    + "could not start; those slots are not searchable. ({})", t.toString());
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
