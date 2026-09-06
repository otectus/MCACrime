package dev.otectus.mcacrime.captivity;

import net.minecraft.world.item.ItemStack;

/**
 * A restraint set aside for a capture that is about to commit, but not yet spent.
 *
 * <p>The order the item used to be consumed in was "take the rope, then try to capture", so every
 * refusal the custody table could raise — already held, over the allowance — burned the rope anyway.
 * Reserving records <em>which</em> stack was going to pay before the commit and leaves the spending
 * until after it: the snapshot is compared against the live slot so an inventory that moved during
 * the same tick fails the check rather than shrinking the wrong stack.
 *
 * @param slot     index into the captor's main inventory
 * @param snapshot a copy of the stack as it stood when the reservation was taken
 */
public record RestraintReservation(int slot, ItemStack snapshot) {
}
