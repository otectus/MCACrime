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
    /** The provider/item last resolved, so a change of either can be reported exactly once. */
    private static volatile ResourceLocation lastActiveId;
    private static volatile ResourceLocation lastActiveItem;

    static {
        register(EmeraldCurrency.INSTANCE);
        register(ItemCurrency.INSTANCE);
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

    /**
     * The currency behind a provider id, registered or item-bound.
     *
     * <p>{@code mcacrime:item/<namespace>/<path>} is not in the registry: it is the per-item id a
     * receipt records, and it resolves to a view pinned to that item. It resolves to <em>nothing</em>
     * when the item is gone, which is what makes a payment owed in a removed mod's coin stay owed
     * instead of being handed over in whatever the server charges in today.
     */
    public static Optional<Currency> byId(ResourceLocation id) {
        if (id == null) {
            return Optional.empty();
        }
        Currency registered = REGISTRY.get(id);
        if (registered != null) {
            return Optional.of(registered);
        }
        return ItemCurrency.itemIdOf(id).flatMap(ItemCurrency::forItem);
    }

    /** Re-reads the currency config. Called at common setup and on every config reload. */
    public static void reload() {
        String configuredId;
        String configuredItem;
        try {
            configuredId = McaCrimeConfig.COMMON.currencyId.get();
            configuredItem = McaCrimeConfig.COMMON.currencyItem.get();
        } catch (IllegalStateException e) {
            return; // config not loaded yet; the setup call does this properly
        }
        reload(configuredId, configuredItem);
    }

    /**
     * Resolves the active currency from explicit values, so a test never needs a loaded config.
     *
     * <p>Order matters: the item behind {@code mcacrime:item} is resolved <em>before</em> the provider
     * is chosen, or the first transaction after a reload would be charged in whatever item the previous
     * config named.
     *
     * <p>Selecting {@code mcacrime:item} makes the active currency a view pinned to the configured
     * item, whose id is {@code mcacrime:item/<namespace>/<path>} rather than {@code mcacrime:item}.
     * With no item registry — a unit test, or config load before registries are built — nothing can be
     * pinned, so the active currency is the emerald fallback and {@code active().id()} is
     * {@code mcacrime:emerald}.
     */
    public static void reload(String currencyId, String currencyItem) {
        dev.otectus.mcacrime.compat.NumismaticBridge.registerIfPresent();
        ItemCurrency.reload(currencyItem);

        ResourceLocation id = currencyId == null ? null : ResourceLocation.tryParse(currencyId.trim());
        Currency resolved = id == null ? null : REGISTRY.get(id);
        if (resolved == null) {
            if (id == null || !id.equals(warnedFor)) {
                McaCrime.LOGGER.warn("MCA: Crime integrations.currencyId '{}' is not a registered currency. "
                                + "Falling back to '{}': fines, bail, ransom and theft will use emeralds.",
                        currencyId, EmeraldCurrency.ID);
                warnedFor = id;
            }
            noteChange(EmeraldCurrency.INSTANCE);
            active = EmeraldCurrency.INSTANCE;
            return;
        }
        warnedFor = null;
        // The item currency is selected as one provider but charged as one item: the active view is
        // pinned to the configured item so its id names that item, and a receipt written today cannot
        // be paid in whatever tomorrow's config names. Without registries nothing resolves, so this is
        // the emerald fallback and the active id is 'mcacrime:emerald'.
        Currency effective = resolved == ItemCurrency.INSTANCE
                ? ItemCurrency.forItemOrEmeralds(ItemCurrency.INSTANCE.itemId())
                : resolved;
        noteChange(effective);
        active = effective;
    }

    /**
     * Says once, loudly, that money already owed does not follow the config.
     *
     * <p>A queued receipt records the provider id it was created with — for an item currency that id
     * names the item — and is paid in that. Changing the setting mid-world is therefore legal but surprising:
     * bounties banked yesterday still arrive as yesterday's coin. Silence here is how that becomes a
     * bug report about "payments in the wrong item".
     */
    private static void noteChange(Currency resolved) {
        ResourceLocation resolvedItem = resolved.itemForm().orElse(null);
        ResourceLocation previousId = lastActiveId;
        ResourceLocation previousItem = lastActiveItem;
        lastActiveId = resolved.id();
        lastActiveItem = resolvedItem;
        if (previousId == null) {
            return; // first resolution of the run: nothing has been owed in anything else yet
        }
        if (!previousId.equals(lastActiveId) || !java.util.Objects.equals(previousItem, resolvedItem)) {
            McaCrime.LOGGER.warn("MCA: Crime currency changed to '{}'{}. Payments already queued keep the "
                            + "currency they were earned in; only new ones use this.",
                    lastActiveId, resolvedItem == null ? "" : " (" + resolvedItem + ")");
        }
    }
}
