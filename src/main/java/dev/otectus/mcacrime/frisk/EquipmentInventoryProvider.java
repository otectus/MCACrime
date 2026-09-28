package dev.otectus.mcacrime.frisk;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What a non-player subject is wearing and holding (M5.2).
 *
 * <p>Reached through vanilla's {@code EquipmentSlot} rather than any MCA type, because an MCA
 * villager is a {@code net.minecraft.world.entity.npc.Villager} and its worn gear is vanilla
 * equipment. That is the same technique the rest of this mod uses for anything MCA inherits rather
 * than declares, and it is why searching a villager works with no binding member at all.
 *
 * <p>A villager's <em>trade offers</em> are not here and never will be. They are not inventory
 * stacks; taking one would delete a trade.
 */
public final class EquipmentInventoryProvider implements InventoryProvider {

    public static final String ID = "equipment";

    private static final EquipmentSlot[] ORDER = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND
    };

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(LivingEntity subject) {
        return subject != null && !(subject instanceof Player);
    }

    @Override
    public List<FriskSlotRef> slots(LivingEntity subject) {
        if (!supports(subject)) {
            return List.of();
        }
        List<FriskSlotRef> refs = new ArrayList<>(ORDER.length);
        for (EquipmentSlot slot : ORDER) {
            refs.add(new FriskSlotRef(ID, kindOf(slot), slot.ordinal()));
        }
        return refs;
    }

    private static FriskSlotKind kindOf(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD, CHEST, LEGS, FEET -> FriskSlotKind.ARMOUR;
            case OFFHAND -> FriskSlotKind.OFFHAND;
            default -> FriskSlotKind.HOTBAR;
        };
    }

    private static EquipmentSlot slotOf(FriskSlotRef ref) {
        EquipmentSlot[] all = EquipmentSlot.values();
        return ref != null && ID.equals(ref.providerId()) && ref.index() < all.length
                ? all[ref.index()] : null;
    }

    @Override
    public ItemStack peek(LivingEntity subject, FriskSlotRef ref) {
        EquipmentSlot slot = slotOf(ref);
        return subject != null && slot != null ? subject.getItemBySlot(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack extract(LivingEntity subject, FriskSlotRef ref, int count) {
        EquipmentSlot slot = slotOf(ref);
        if (subject == null || slot == null || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack present = subject.getItemBySlot(slot);
        if (present.isEmpty() || present.getCount() < count) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = present.split(count);
        subject.setItemSlot(slot, present.isEmpty() ? ItemStack.EMPTY : present);
        return taken;
    }

    @Override
    public boolean restore(LivingEntity subject, FriskSlotRef ref, ItemStack stack) {
        EquipmentSlot slot = slotOf(ref);
        if (subject == null || slot == null || stack == null || stack.isEmpty()) {
            return false;
        }
        ItemStack present = subject.getItemBySlot(slot);
        if (present.isEmpty()) {
            subject.setItemSlot(slot, stack);
            return true;
        }
        if (ItemStack.isSameItemSameTags(present, stack)
                && present.getCount() + stack.getCount() <= present.getMaxStackSize()) {
            present.grow(stack.getCount());
            subject.setItemSlot(slot, present);
            return true;
        }
        return false;
    }
}
