package dev.otectus.mcacrime.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** The default emerald-based {@link Currency} (spec §11.5). Counts/consumes emeralds in the main inventory. */
public final class EmeraldCurrency implements Currency {

    /** The id {@code integrations.currencyId} defaults to. */
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mcacrime", "emerald");

    public static final EmeraldCurrency INSTANCE = new EmeraldCurrency();

    private EmeraldCurrency() {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public long balance(ServerPlayer player) {
        long count = 0L;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (stack.is(Items.EMERALD)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    @Override
    public long debit(ServerPlayer player, long requested, TransactionReason reason) {
        if (requested <= 0L) {
            return 0L;
        }
        long remaining = requested;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size() && remaining > 0L; i++) {
            ItemStack stack = inv.items.get(i);
            if (stack.is(Items.EMERALD)) {
                int take = (int) Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
            }
        }
        inv.setChanged();
        return requested - remaining;
    }

    @Override
    public void credit(ServerPlayer player, long amount, TransactionReason reason) {
        long remaining = Math.max(0L, amount);
        while (remaining > 0L) {
            int stackSize = (int) Math.min(remaining, Items.EMERALD.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(Items.EMERALD, stackSize);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= stackSize;
        }
    }

    @Override
    public Component format(long amount) {
        return Component.translatable("mcacrime.currency.emerald", amount);
    }

    @Override
    public long stacksNeeded(long amount) {
        long perStack = Math.max(1, Items.EMERALD.getDefaultMaxStackSize());
        return amount <= 0L ? 0L : (amount + perStack - 1L) / perStack;
    }

    @Override
    public List<ItemStack> toStacks(long amount) {
        List<ItemStack> stacks = new ArrayList<>();
        long remaining = Math.max(0L, amount);
        while (remaining > 0L) {
            int stackSize = (int) Math.min(remaining, Items.EMERALD.getDefaultMaxStackSize());
            stacks.add(new ItemStack(Items.EMERALD, stackSize));
            remaining -= stackSize;
        }
        return stacks;
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
