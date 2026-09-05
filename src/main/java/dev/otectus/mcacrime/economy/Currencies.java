package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The currency registry, and the single answer to what this server charges in (0.5.1).
 *
 * <p>{@code Currency} existed before this release and every call site went straight to
 * {@code EmeraldCurrency.INSTANCE}, which made the abstraction decorative and made
 * {@code integrations.currencyId} a promise nothing kept. Everything that moves money now asks
 * {@link #active()}, so the key is real and an economy mod has somewhere to register.
 *
 * <p>An unknown id falls back to emeralds and warns <em>once</em>. Refusing to run would punish a
 * server for a typo by taking fines, bail and ransom offline; warning per transaction would bury the
 * one line that says what is wrong.
 */
public final class Currencies {

    private static final Map<ResourceLocation, Currency> REGISTRY = new ConcurrentHashMap<>();

    private static volatile Currency active = EmeraldCurrency.INSTANCE;
    /** The id already reported as unknown, so a reload does not repeat the warning. */
    private static volatile ResourceLocation warnedFor;

    static {
        register(EmeraldCurrency.INSTANCE);
    }

    private Currencies() {
    }

    /** The currency this server charges in. Never null; emeralds until told otherwise. */
    public static Currency active() {
        return active;
    }

    /** Adds a currency. An economy mod calls this during common setup, before the first reload. */
    public static void register(Currency currency) {
        if (currency != null && currency.id() != null) {
            REGISTRY.put(currency.id(), currency);
        }
    }

    public static Optional<Currency> byId(ResourceLocation id) {
        return id == null ? Optional.empty() : Optional.ofNullable(REGISTRY.get(id));
    }

    /** Re-reads {@code integrations.currencyId}. Called at common setup and on every config reload. */
    public static void reload() {
        String configured;
        try {
            configured = McaCrimeConfig.COMMON.currencyId.get();
        } catch (IllegalStateException e) {
            return; // config not loaded yet; the setup call does this properly
        }
        ResourceLocation id = configured == null ? null : ResourceLocation.tryParse(configured.trim());
        Currency resolved = id == null ? null : REGISTRY.get(id);
        if (resolved == null) {
            if (id == null || !id.equals(warnedFor)) {
                McaCrime.LOGGER.warn("MCA: Crime integrations.currencyId '{}' is not a registered currency. "
                                + "Falling back to '{}': fines, bail, ransom and theft will use emeralds.",
                        configured, EmeraldCurrency.ID);
                warnedFor = id;
            }
            active = EmeraldCurrency.INSTANCE;
            return;
        }
        warnedFor = null;
        active = resolved;
    }
}
