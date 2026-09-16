package dev.otectus.mcacrime.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Mask Station's output slot: one owner, one commit (0.7.2 §8.2, §8.3).
 *
 * <p>What sits here is a preview, not stock. It was assembled from the current inputs so the player
 * can see what they are about to make, and it becomes a real item at exactly one moment — when
 * {@link #onTake} runs, having passed {@link #mayPickup}. That single ownership is the whole design:
 * ingredients are debited in the menu's commit and nowhere else, so no route can pay twice and none
 * can pay nothing.
 *
 * <p>{@link #remove} is inherited on purpose. It lifts the preview out of its one-slot container and
 * does not consume anything; vanilla calls it immediately before {@code onTake}, and a debit in both
 * would be the classic double-charge (§8.2).
 */
public class MaskStationResultSlot extends Slot {

    private final MaskStationMenu menu;

    public MaskStationResultSlot(MaskStationMenu menu, Container container, int index, int x, int y) {
        super(container, index, x, y);
        this.menu = menu;
    }

    /** Nothing is ever stored here, so nothing may be placed here — including by a hotbar swap. */
    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    /** Only a fully payable, fully deliverable craft may leave this slot. */
    @Override
    public boolean mayPickup(Player player) {
        return hasItem() && menu.craftable();
    }

    @Override
    public void onTake(Player player, ItemStack stack) {
        menu.commitOne(player);
        super.onTake(player, stack);
    }
}
