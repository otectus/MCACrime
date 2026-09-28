package dev.otectus.mcacrime.frisk;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The vanilla player inventory: hotbar, main grid, armour and off hand (M5.2).
 *
 * <p>Indices are vanilla's own, so a receipt naming slot 17 means the same thing a year from now.
 * The display order is the one a search reads in — worn first, hands next, pockets last — which is
 * why the list is built rather than taken straight from {@code Inventory}.
 */
public final class PlayerInventoryProvider implements InventoryProvider {

    public static final String ID = "vanilla";

    /** Vanilla's own numbering: 0-8 hotbar, 9-35 main, 36-39 armour, 40 off hand. */
    private static final int ARMOUR_BASE = 36;
    private static final int OFFHAND = 40;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(LivingEntity subject) {
        return subject instanceof Player;
    }

    @Override
    public List<FriskSlotRef> slots(LivingEntity subject) {
        if (!(subject instanceof Player)) {
            return List.of();
        }
        List<FriskSlotRef> refs = new ArrayList<>(41);
        for (int i = 3; i >= 0; i--) {
            refs.add(new FriskSlotRef(ID, FriskSlotKind.ARMOUR, ARMOUR_BASE + i));
        }
        refs.add(new FriskSlotRef(ID, FriskSlotKind.OFFHAND, OFFHAND));
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            refs.add(new FriskSlotRef(ID, FriskSlotKind.HOTBAR, i));
        }
        for (int i = Inventory.getSelectionSize(); i < 36; i++) {
            refs.add(new FriskSlotRef(ID, FriskSlotKind.MAIN, i));
        }
        return refs;
    }

    @Override
    public ItemStack peek(LivingEntity subject, FriskSlotRef ref) {
        return subject instanceof Player player && mine(ref)
                ? player.getInventory().getItem(ref.index()) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack extract(LivingEntity subject, FriskSlotRef ref, int count) {
        if (!(subject instanceof Player player) || !mine(ref) || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack present = player.getInventory().getItem(ref.index());
        if (present.isEmpty() || present.getCount() < count) {
            return ItemStack.EMPTY; // exactly the count asked for, or nothing at all
        }
        ItemStack taken = player.getInventory().removeItem(ref.index(), count);
        player.getInventory().setChanged();
        return taken;
    }

    @Override
    public boolean restore(LivingEntity subject, FriskSlotRef ref, ItemStack stack) {
        if (!(subject instanceof Player player) || !mine(ref) || stack == null || stack.isEmpty()) {
            return false;
        }
        ItemStack present = player.getInventory().getItem(ref.index());
        if (present.isEmpty()) {
            player.getInventory().setItem(ref.index(), stack);
            player.getInventory().setChanged();
            return true;
        }
        if (ItemStack.isSameItemSameComponents(present, stack)
                && present.getCount() + stack.getCount() <= present.getMaxStackSize()) {
            present.grow(stack.getCount());
            player.getInventory().setChanged();
            return true;
        }
        // The slot moved on. Giving it back anywhere in the same inventory still returns the goods.
        return player.getInventory().add(stack);
    }

    private static boolean mine(FriskSlotRef ref) {
        return ref != null && ID.equals(ref.providerId()) && ref.index() >= 0 && ref.index() <= OFFHAND;
    }
}
