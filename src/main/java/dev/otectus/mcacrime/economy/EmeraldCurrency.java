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
}
