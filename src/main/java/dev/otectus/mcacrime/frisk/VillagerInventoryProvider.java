package dev.otectus.mcacrime.frisk;

import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a villager is carrying, as opposed to wearing (M5.2).
 *
 * <p>Reached through {@link McaCompat#villagerInventory}, which is the compat seam this is required
 * to go through. If MCA is absent, if the binding did not resolve, or if the installed MCA no longer
 * exposes an inventory at all, the facade answers empty and this provider offers no slots — a
 * villager who cannot be read is simply a villager with nothing to find, not a crash and not a
 * refusal to open the screen.
 */
public final class VillagerInventoryProvider implements InventoryProvider {

    public static final String ID = "villager";

    private static Optional<Container> container(LivingEntity subject) {
        return McaCompat.villagerInventory(subject);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(LivingEntity subject) {
        return container(subject).isPresent();
    }

    @Override
    public List<FriskSlotRef> slots(LivingEntity subject) {
        Optional<Container> held = container(subject);
        if (held.isEmpty()) {
            return List.of();
        }
        int size = held.get().getContainerSize();
        List<FriskSlotRef> refs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            refs.add(new FriskSlotRef(ID, FriskSlotKind.EQUIPMENT, i));
        }
        return refs;
    }

    @Override
    public ItemStack peek(LivingEntity subject, FriskSlotRef ref) {
        if (ref == null || !ID.equals(ref.providerId())) {
            return ItemStack.EMPTY;
        }
        return container(subject)
                .filter(c -> ref.index() < c.getContainerSize())
                .map(c -> c.getItem(ref.index()))
                .orElse(ItemStack.EMPTY);
    }

    @Override
    public ItemStack extract(LivingEntity subject, FriskSlotRef ref, int count) {
        if (ref == null || !ID.equals(ref.providerId()) || count <= 0) {
            return ItemStack.EMPTY;
        }
        Optional<Container> held = container(subject);
        if (held.isEmpty() || ref.index() >= held.get().getContainerSize()) {
            return ItemStack.EMPTY;
        }
        Container inventory = held.get();
        ItemStack present = inventory.getItem(ref.index());
        if (present.isEmpty() || present.getCount() < count) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = inventory.removeItem(ref.index(), count);
        inventory.setChanged();
        return taken;
    }

    @Override
    public boolean restore(LivingEntity subject, FriskSlotRef ref, ItemStack stack) {
        if (ref == null || !ID.equals(ref.providerId()) || stack == null || stack.isEmpty()) {
            return false;
        }
        Optional<Container> held = container(subject);
        if (held.isEmpty() || ref.index() >= held.get().getContainerSize()) {
            return false;
        }
        Container inventory = held.get();
        ItemStack present = inventory.getItem(ref.index());
        if (present.isEmpty()) {
            inventory.setItem(ref.index(), stack);
            inventory.setChanged();
            return true;
        }
        if (ItemStack.isSameItemSameTags(present, stack)
                && present.getCount() + stack.getCount() <= present.getMaxStackSize()) {
            present.grow(stack.getCount());
            inventory.setChanged();
            return true;
        }
        return false;
    }
}
