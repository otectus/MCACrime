package dev.otectus.mcacrime.economy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Every inventory operation an item-backed currency needs, over an arbitrary {@link Item} (0.7.1).
 *
 * <p>{@code EmeraldCurrency} and {@code ItemCurrency} are the same code with a different item in it,
 * and the bounty payout had a third copy of the credit walk inlined in {@code BountyService}. This
 * class is that code written once; the currencies are thin bindings of an item to it.
 *
 * <h2>Only untagged stacks are money</h2>
 *
 * <p>Counting, spending and merging all require {@code stack.is(item) && !stack.hasTag()}. A renamed
 * emerald, an enchanted coin, a stack carrying an economy mod's own NBT, or anything a player has
 * deliberately made special is <b>not</b> currency — a fine must never quietly consume somebody's
 * named keepsake because it happens to share an item id, and merging into a tagged stack would erase
 * the very thing that made it worth keeping. The cost is that such stacks also do not count towards a
 * balance, which is the safe direction to be wrong in: the player is told they cannot pay rather than
 * being charged something they did not intend to spend.
 */
final class ItemStackCurrencySupport {

    private ItemStackCurrencySupport() {
    }

    /** How many untagged {@code item} the player is carrying in their main inventory. */
    static long count(Inventory inv, Item item) {
        if (inv == null || item == null) {
            return 0L;
        }
        long total = 0L;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (isMoney(stack, item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Removes up to {@code requested} untagged {@code item}; returns how many were actually removed. */
    static long remove(Inventory inv, Item item, long requested) {
        if (inv == null || item == null || requested <= 0L) {
            return 0L;
        }
        long remaining = requested;
        for (int i = 0; i < inv.items.size() && remaining > 0L; i++) {
            ItemStack stack = inv.items.get(i);
            if (isMoney(stack, item)) {
                int take = (int) Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
            }
        }
        inv.setChanged();
        return requested - remaining;
    }

    /**
     * Credits {@code amount} without ever dropping anything on the ground, and reports the remainder.
     *
     * <p>The generalised form of the walk the bounty payout used to inline. Partial stacks of the
     * currency item are topped up first, then empty slots are filled — and when the inventory runs out
     * the leftover comes back as a number instead of falling at the player's feet, because a bounty
     * receipt can re-queue a remainder but cannot recover a stack a hostile mob walked over.
     *
     * @return the undelivered remainder, 0 when everything fit
     */
    static long creditBounded(Inventory inv, Item item, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        if (inv == null || item == null) {
            return amount;
        }
        int maxStack = Math.max(1, item.getMaxStackSize());
        long left = amount;
        for (int i = 0; i < inv.items.size() && left > 0L; i++) {
            ItemStack stack = inv.items.get(i);
            if (isMoney(stack, item)) {
                int give = (int) Math.min(Math.max(0, stack.getMaxStackSize() - stack.getCount()), left);
                stack.grow(give);
                left -= give;
            }
        }
        for (int i = 0; i < inv.items.size() && left > 0L; i++) {
            if (inv.items.get(i).isEmpty()) {
                int give = (int) Math.min(maxStack, left);
                inv.items.set(i, new ItemStack(item, give));
                left -= give;
            }
        }
        inv.setChanged();
        return left;
    }

    /** Gives {@code amount} to the player; whatever does not fit drops at their feet. */
    static void give(ServerPlayer player, Item item, long amount) {
        if (player == null || item == null) {
            return;
        }
        int maxStack = Math.max(1, item.getMaxStackSize());
        long remaining = Math.max(0L, amount);
        while (remaining > 0L) {
            int stackSize = (int) Math.min(remaining, maxStack);
            ItemStack stack = new ItemStack(item, stackSize);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= stackSize;
        }
    }

    /** The item form of {@code amount}, split into stacks. */
    static List<ItemStack> toStacks(Item item, long amount) {
        List<ItemStack> stacks = new ArrayList<>();
        if (item == null) {
            return stacks;
        }
        for (int count : ItemCurrencyMath.splitCounts(amount, Math.max(1, item.getMaxStackSize()))) {
            stacks.add(new ItemStack(item, count));
        }
        return stacks;
    }

    /** How many stacks {@link #toStacks} would produce, without producing them. */
    static long stacksNeeded(Item item, long amount) {
        return item == null ? 0L : ItemCurrencyMath.stacksNeeded(amount, Math.max(1, item.getMaxStackSize()));
    }

    /** See the class note: tagged stacks are somebody's property, not the server's small change. */
    private static boolean isMoney(ItemStack stack, Item item) {
        return stack != null && stack.is(item) && !stack.hasTag();
    }
}
