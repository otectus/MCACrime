package dev.otectus.mcacrime.economy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The default emerald-based {@link Currency} (spec §11.5). Counts/consumes emeralds in the main inventory. */
public final class EmeraldCurrency implements Currency {

    public static final EmeraldCurrency INSTANCE = new EmeraldCurrency();

    private EmeraldCurrency() {
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
    public boolean tryCharge(ServerPlayer player, long amount) {
        if (amount <= 0L) {
            return true;
        }
        if (balance(player) < amount) {
            return false; // atomic: charge nothing on insufficient funds
        }
        long remaining = amount;
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
        return true;
    }

    /** Gives {@code amount} emeralds to a player (ransom payout, mug loot); overflow drops at their feet. */
    public void grant(ServerPlayer player, long amount) {
        long remaining = Math.max(0L, amount);
        while (remaining > 0L) {
            int stackSize = (int) Math.min(remaining, Items.EMERALD.getMaxStackSize());
            ItemStack stack = new ItemStack(Items.EMERALD, stackSize);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= stackSize;
        }
    }
}
