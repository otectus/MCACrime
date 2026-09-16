package dev.otectus.mcacrime.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * The default emerald-based {@link Currency} (spec §11.5). Counts/consumes emeralds in the main inventory.
 *
 * <p>Since 0.7.1 the body is {@link ItemStackCurrencySupport} bound to {@code minecraft:emerald}: this
 * is {@code ItemCurrency} with the item fixed and the id kept, so the default currency cannot drift
 * away from the configurable one. The id, the lang key and the behaviour are unchanged, which matters
 * because {@code mcacrime:emerald} is written into every queued receipt already on disk.
 */
public final class EmeraldCurrency implements Currency {

    /** The id {@code integrations.currencyId} defaults to. */
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mcacrime", "emerald");

    public static final EmeraldCurrency INSTANCE = new EmeraldCurrency();

    private static final ResourceLocation ITEM_ID =
            ResourceLocation.fromNamespaceAndPath("minecraft", "emerald");

    private EmeraldCurrency() {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public long balance(ServerPlayer player) {
        return player == null ? 0L : ItemStackCurrencySupport.count(player.getInventory(), Items.EMERALD);
    }

    @Override
    public long debit(ServerPlayer player, long requested, TransactionReason reason) {
        return player == null ? 0L
                : ItemStackCurrencySupport.remove(player.getInventory(), Items.EMERALD, requested);
    }

    @Override
    public void credit(ServerPlayer player, long amount, TransactionReason reason) {
        ItemStackCurrencySupport.give(player, Items.EMERALD, amount);
    }

    @Override
    public long creditBounded(ServerPlayer player, long amount, TransactionReason reason) {
        return player == null ? amount
                : ItemStackCurrencySupport.creditBounded(player.getInventory(), Items.EMERALD, amount);
    }

    @Override
    public Component format(long amount) {
        return Component.translatable("mcacrime.currency.emerald", amount);
    }

    @Override
    public long stacksNeeded(long amount) {
        return ItemStackCurrencySupport.stacksNeeded(Items.EMERALD, amount);
    }

    @Override
    public List<ItemStack> toStacks(long amount) {
        return ItemStackCurrencySupport.toStacks(Items.EMERALD, amount);
    }

    @Override
    public Optional<ResourceLocation> itemForm() {
        return Optional.of(ITEM_ID);
    }

    /**
     * Gives {@code amount} emeralds to a player; overflow drops at their feet.
     *
     * @deprecated use {@link #credit(ServerPlayer, long, TransactionReason)} and name the reason.
     */
    @Deprecated
    public void grant(ServerPlayer player, long amount) {
        credit(player, amount, TransactionReason.OTHER);
    }
}
