package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * A currency that is one registered item, named by {@code integrations.currencyItem} (0.7.1).
 *
 * <p>The point of this class is that physical money in MCA: Crime is no longer <em>inherently</em>
 * emeralds. A server that wants fines paid in gold nuggets, a modpack with a coin item, a pack that
 * has removed emeralds entirely — all of them used to need an economy mod and a registered provider.
 * They now need one config line, because one item equals one unit and everything else is the same
 * inventory walk emeralds already used.
 *
 * <p>The item is resolved once per config load and held in a volatile field rather than looked up per
 * transaction: {@code BuiltInRegistries} lookups are cheap but not free, and a fine should not pay for a
 * registry hash on every charge. {@link #reload(String)} is the only writer.
 */
public final class ItemCurrency implements Currency {

    /** The id {@code integrations.currencyId} uses to select this provider. */
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mcacrime", "item");

    /** Declared before INSTANCE: the instance initialiser reads it, and static order is textual. */
    private static final ResourceLocation EMERALD_ID =
            ResourceLocation.fromNamespaceAndPath("minecraft", "emerald");

    /** The path prefix of a per-item provider id; see {@link #boundId}. */
    private static final String BOUND_PREFIX = "item/";

    public static final ItemCurrency INSTANCE = new ItemCurrency();


    /** The configured value already reported as unusable, so a reload does not repeat the warning. */
    private static volatile String warnedFor;

    /**
     * The resolved item, or null for "not resolved yet, so emeralds".
     *
     * <p>Null rather than {@code Items.EMERALD} on purpose: a field initialiser would force the
     * {@code Items} class to initialise the moment {@code Currencies} registers this instance, which is
     * a whole vanilla registry's worth of static work at an hour when the registries may not exist.
     * {@link #currentItem()} resolves it at the point of use, where a game is definitely running.
     */
    private volatile Item item;
    private volatile ResourceLocation itemId = EMERALD_ID;

    private ItemCurrency() {
    }

    /**
     * Re-reads {@code integrations.currencyItem}.
     *
     * <p>Safe to call before the item registry exists. That is not defensive padding: config load runs
     * early, and a {@code BuiltInRegistries} touch at the wrong moment throws something that is not a
     * {@code RuntimeException}. Anything unexpected leaves the currently resolved item in place, which
     * is emeralds on a fresh server and the last good value on a reload — never a half-resolved state
     * that charges players in air.
     *
     * <p>An unusable value warns <b>once per distinct value</b> and pays in emeralds, for the same
     * reason {@code Currencies} does: a typo must not take fines, bail and ransom offline, and warning
     * per transaction would bury the one line that says what to fix.
     */
    public static void reload(String configuredId) {
        String raw = configuredId == null ? "" : configuredId.trim();
        ResourceLocation id;
        Item resolved;
        try {
            id = raw.isEmpty() ? null : ResourceLocation.tryParse(raw);
            resolved = lookup(id);
        } catch (Throwable registryUnavailable) {
            return; // registries not built yet (or gone); keep whatever is resolved, setup calls again
        }
        if (resolved == null) {
            if (!raw.equals(warnedFor)) {
                McaCrime.LOGGER.warn("MCA: Crime integrations.currencyItem '{}' is not a registered item. "
                                + "'{}' will keep paying in emeralds until it is fixed.",
                        configuredId, ID);
                warnedFor = raw;
            }
            INSTANCE.item = null;
            INSTANCE.itemId = EMERALD_ID;
            return;
        }
        warnedFor = null;
        INSTANCE.item = resolved;
        INSTANCE.itemId = id;
    }

    /**
     * A currency view bound to one specific item rather than to the configured one.
     *
     * <p>Exists for queued receipts. A bounty earned when the server paid in gold nuggets is owed in
     * gold nuggets, even if the operator has since switched to emeralds — the receipt recorded a
     * provider id and this is how that id becomes something that can pay it.
     *
     * <p>Empty when the item does not resolve, and deliberately <em>not</em> emeralds: a receipt whose
     * item has left the pack must stay owed until it comes back, because paying it in the currency
     * that happens to be configured today is how a gold-nugget debt becomes an emerald payout. The
     * config path wants the opposite answer and calls {@link #forItemOrEmeralds} for it.
     */
    public static Optional<Currency> forItem(ResourceLocation id) {
        Item resolved;
        try {
            resolved = lookup(id);
        } catch (Throwable registryUnavailable) {
            resolved = null;
        }
        return resolved == null ? Optional.empty() : Optional.of(new BoundItemCurrency(id, resolved));
    }

    /**
     * {@link #forItem} for the configured item, where an unusable value must not take money offline.
     *
     * <p>Falls back to emeralds, which is what {@code integrations.currencyItem} has always done for a
     * typo. Only {@code Currencies.reload} may use this; receipt resolution must not, or it would pay
     * yesterday's debt in today's coin.
     */
    public static Currency forItemOrEmeralds(ResourceLocation id) {
        return forItem(id).orElse(EmeraldCurrency.INSTANCE);
    }

    /**
     * The provider id of the currency bound to {@code itemId}: {@code mcacrime:item/<namespace>/<path>}.
     *
     * <p>One id per item, because a provider id is the only thing a queued receipt records about what
     * it is owed in and {@code mcacrime:item} alone means "whatever the config says now". A slash is
     * legal in a {@code ResourceLocation} path, so this parses back with {@code tryParse} and compares
     * with plain string equality everywhere a provider id is compared.
     */
    public static ResourceLocation boundId(ResourceLocation itemId) {
        ResourceLocation id = itemId == null ? EMERALD_ID : itemId;
        return ResourceLocation.fromNamespaceAndPath(ID.getNamespace(),
                BOUND_PREFIX + id.getNamespace() + "/" + id.getPath());
    }

    /** The item id inside a {@link #boundId}, or empty when {@code id} is not one. */
    public static Optional<ResourceLocation> itemIdOf(ResourceLocation id) {
        if (id == null || !ID.getNamespace().equals(id.getNamespace()) || !id.getPath().startsWith(BOUND_PREFIX)) {
            return Optional.empty();
        }
        String remainder = id.getPath().substring(BOUND_PREFIX.length());
        int split = remainder.indexOf('/');
        if (split <= 0 || split == remainder.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(ResourceLocation.tryParse(
                remainder.substring(0, split) + ":" + remainder.substring(split + 1)));
    }

    /** The registered item for {@code id}, or null when it is absent, unparseable or air. */
    private static Item lookup(ResourceLocation id) {
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        Item candidate = BuiltInRegistries.ITEM.get(id);
        return candidate == null || candidate == Items.AIR ? null : candidate;
    }

    /** The item being charged in; emeralds until {@link #reload} resolves something else. */
    private Item currentItem() {
        Item current = item;
        return current == null ? Items.EMERALD : current;
    }

    /** The registry id of the item currently being charged in. For diagnostics and receipts. */
    public ResourceLocation itemId() {
        return itemId;
    }

    /** The item currently being charged in. For diagnostics. */
    public Item item() {
        return currentItem();
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public long balance(ServerPlayer player) {
        return player == null ? 0L : ItemStackCurrencySupport.count(player.getInventory(), currentItem());
    }

    @Override
    public long debit(ServerPlayer player, long requested, TransactionReason reason) {
        return player == null ? 0L : ItemStackCurrencySupport.remove(player.getInventory(), currentItem(), requested);
    }

    @Override
    public void credit(ServerPlayer player, long amount, TransactionReason reason) {
        ItemStackCurrencySupport.give(player, currentItem(), amount);
    }

    @Override
    public long creditBounded(ServerPlayer player, long amount, TransactionReason reason) {
        return player == null ? amount : ItemStackCurrencySupport.creditBounded(player.getInventory(), currentItem(), amount);
    }

    @Override
    public Component format(long amount) {
        return Component.translatable("mcacrime.currency.item", amount, currentItem().getDescription());
    }

    @Override
    public long stacksNeeded(long amount) {
        return ItemStackCurrencySupport.stacksNeeded(currentItem(), amount);
    }

    @Override
    public List<ItemStack> toStacks(long amount) {
        return ItemStackCurrencySupport.toStacks(currentItem(), amount);
    }

    @Override
    public Optional<ResourceLocation> itemForm() {
        // itemId is reset by reload(); a failed lookup leaves the emerald fallback, never a null.
        ResourceLocation id = itemId;
        return Optional.of(id == null ? EMERALD_ID : id);
    }

    /** The {@link #forItem} view: identical behaviour, pinned to one item instead of the config. */
    private static final class BoundItemCurrency implements Currency {

        private final ResourceLocation boundItemId;
        private final ResourceLocation boundId;
        private final Item boundItem;

        BoundItemCurrency(ResourceLocation boundItemId, Item boundItem) {
            this.boundItemId = boundItemId;
            this.boundId = boundId(boundItemId);
            this.boundItem = boundItem;
        }

        @Override
        public ResourceLocation id() {
            // Per item, not per provider: this id is written onto receipts, and 'mcacrime:item' would
            // mean "whatever the config says when it is paid".
            return boundId;
        }

        @Override
        public long balance(ServerPlayer player) {
            return player == null ? 0L : ItemStackCurrencySupport.count(player.getInventory(), boundItem);
        }

        @Override
        public long debit(ServerPlayer player, long requested, TransactionReason reason) {
            return player == null ? 0L
                    : ItemStackCurrencySupport.remove(player.getInventory(), boundItem, requested);
        }

        @Override
        public void credit(ServerPlayer player, long amount, TransactionReason reason) {
            ItemStackCurrencySupport.give(player, boundItem, amount);
        }

        @Override
        public long creditBounded(ServerPlayer player, long amount, TransactionReason reason) {
            return player == null ? amount
                    : ItemStackCurrencySupport.creditBounded(player.getInventory(), boundItem, amount);
        }

        @Override
        public Component format(long amount) {
            return Component.translatable("mcacrime.currency.item", amount, boundItem.getDescription());
        }

        @Override
        public long stacksNeeded(long amount) {
            return ItemStackCurrencySupport.stacksNeeded(boundItem, amount);
        }

        @Override
        public List<ItemStack> toStacks(long amount) {
            return ItemStackCurrencySupport.toStacks(boundItem, amount);
        }

        @Override
        public Optional<ResourceLocation> itemForm() {
            return Optional.of(boundItemId);
        }
    }
}
