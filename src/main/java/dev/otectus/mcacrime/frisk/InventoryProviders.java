package dev.otectus.mcacrime.frisk;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every place a search looks, in the order it looks (M5.2).
 *
 * <p>A fixed registry rather than an extension point, for now: the three providers below cover the
 * subjects this release can search, and Curios and Cosmetic Armor join them in M6 through the same
 * interface. Order is display order and receipt order both.
 *
 * <p>A provider that throws is dropped for that subject rather than allowed to abort the search.
 * A search that fails half-way through building its list would show a partial inventory without
 * saying so, which is worse than a provider quietly having nothing to offer.
 */
public final class InventoryProviders {

    private static final Map<String, InventoryProvider> PROVIDERS = new LinkedHashMap<>();

    static {
        register(new PlayerInventoryProvider());
        register(new EquipmentInventoryProvider());
        register(new VillagerInventoryProvider());
    }

    private InventoryProviders() {
    }

    private static void register(InventoryProvider provider) {
        PROVIDERS.put(provider.id(), provider);
    }

    /** How many providers another mod may add. Every one is walked on every search. */
    public static final int MAX_THIRD_PARTY_PROVIDERS = 16;

    private static final java.util.Set<String> SHIPPED = java.util.Set.copyOf(PROVIDERS.keySet());

    /**
     * Adds a place a search looks (0.7.5 M6.4).
     *
     * <p>Reached through {@code api/RestraintRegistrationApi} and by this mod's own Curios and
     * Cosmetic Armor adapters - the same door, so the door cannot rot. A shipped id may never be
     * replaced: a provider that silently took over "player" would be reading somebody's inventory
     * through code MCA: Crime does not own.
     */
    public static synchronized dev.otectus.mcacrime.api.RestraintRegistrationApi.Result
            registerThirdParty(InventoryProvider provider) {
        String id = provider.id();
        if (SHIPPED.contains(id)) {
            return dev.otectus.mcacrime.api.RestraintRegistrationApi.Result.DUPLICATE;
        }
        if (PROVIDERS.containsKey(id)) {
            return dev.otectus.mcacrime.api.RestraintRegistrationApi.Result.DUPLICATE;
        }
        if (PROVIDERS.size() - SHIPPED.size() >= MAX_THIRD_PARTY_PROVIDERS) {
            return dev.otectus.mcacrime.api.RestraintRegistrationApi.Result.FULL;
        }
        PROVIDERS.put(id, provider);
        return dev.otectus.mcacrime.api.RestraintRegistrationApi.Result.ACCEPTED;
    }

    /** Drops every third-party provider. Test-only, and the shutdown path. */
    public static synchronized void resetThirdParty() {
        PROVIDERS.keySet().removeIf(id -> !SHIPPED.contains(id));
    }

    /** Every registered provider id, shipped first. Diagnostics. */
    public static java.util.List<String> ids() {
        return java.util.List.copyOf(PROVIDERS.keySet());
    }

    /** The provider with this id, or null. */
    @Nullable
    public static InventoryProvider byId(@Nullable String id) {
        return id == null ? null : PROVIDERS.get(id);
    }

    /** Every searchable slot of a subject, in display order. */
    public static List<FriskSlotRef> view(@Nullable LivingEntity subject) {
        if (subject == null) {
            return List.of();
        }
        List<FriskSlotRef> refs = new ArrayList<>();
        for (InventoryProvider provider : PROVIDERS.values()) {
            try {
                if (provider.supports(subject)) {
                    refs.addAll(provider.slots(subject));
                }
            } catch (RuntimeException unreadable) {
                // one provider's problem is not the whole search's problem
            }
        }
        return List.copyOf(refs);
    }

    /** What is in a slot, or empty when its provider is gone. */
    public static ItemStack peek(@Nullable LivingEntity subject, @Nullable FriskSlotRef ref) {
        InventoryProvider provider = ref == null ? null : byId(ref.providerId());
        if (subject == null || provider == null) {
            return ItemStack.EMPTY;
        }
        try {
            return provider.peek(subject, ref);
        } catch (RuntimeException unreadable) {
            return ItemStack.EMPTY;
        }
    }
}
