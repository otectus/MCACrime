package dev.otectus.mcacrime.frisk;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a searcher's own inventory would take one exact stack, asked before anything moves (M5.2,
 * condition 6).
 *
 * <p>Pure over a list of slots, so the rule can be asserted without a player: an empty slot takes a
 * whole stack, a partial stack of the same item and tags takes the difference, and a damaged item
 * goes into an empty slot only, which is what vanilla's {@code Inventory.add} does with it. The
 * count is deliberately conservative — the main slots and the off hand, never armour — so a
 * refusal here can only ever be a transfer that would have fitted, never a transfer that then does
 * not, and the transaction's rollback stays a complete undo.
 */
public final class InventoryCapacity {

    private InventoryCapacity() {
    }

    /** The slots {@code Inventory.add} fills, in the order it considers them. */
    public static List<ItemStack> destinationSlots(@Nullable Inventory inventory) {
        if (inventory == null) {
            return List.of();
        }
        List<ItemStack> slots = new ArrayList<>(inventory.items.size() + inventory.offhand.size());
        slots.addAll(inventory.items);
        slots.addAll(inventory.offhand);
        return slots;
    }

    /** {@link #wouldTake(List, ItemStack)} against a live inventory. */
    public static boolean wouldTake(@Nullable Inventory inventory, @Nullable ItemStack probe) {
        return wouldTake(destinationSlots(inventory), probe);
    }

    /**
     * Whether these slots have room for the whole of {@code probe}.
     *
     * <p>Whole or nothing: a stack that would only half fit is refused, because the transaction
     * behind this never splits what it debits.
     */
    public static boolean wouldTake(@Nullable List<ItemStack> slots, @Nullable ItemStack probe) {
        if (probe == null || probe.isEmpty()) {
            return false;
        }
        if (slots == null) {
            return false;
        }
        int needed = probe.getCount();
        int room = 0;
        for (ItemStack slot : slots) {
            if (slot == null || slot.isEmpty()) {
                room += probe.getMaxStackSize();
            } else if (!probe.isDamaged()
                    && ItemStack.isSameItemSameTags(slot, probe)
                    && slot.getCount() < slot.getMaxStackSize()) {
                room += slot.getMaxStackSize() - slot.getCount();
            }
            if (room >= needed) {
                return true;
            }
        }
        return false;
    }
}
