package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.captivity.RestraintReservation;
import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Optional;

/**
 * The mod's item registrations (spec §8.3) — its first items: the three restraints plus a creative tab.
 * Rope also accepts any {@code c:ropes}-tagged item for broad mod compatibility (see {@link
 * #restraintFor}); cuffs and locked cuffs are custom items (vanilla has no equivalent). The Locks Reforged
 * key/removal path for locked cuffs is a Phase 7 seam.
 */
public final class CrimeItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McaCrime.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, McaCrime.MOD_ID);

    public static final DeferredItem<Item> RESTRAINT_ROPE = ITEMS.register("restraint_rope",
            () -> new RestraintItem(RestraintType.ROPE, new Item.Properties()));
    public static final DeferredItem<Item> RESTRAINT_CUFFS = ITEMS.register("restraint_cuffs",
            () -> new RestraintItem(RestraintType.CUFFS, new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> RESTRAINT_LOCKED_CUFFS = ITEMS.register("restraint_locked_cuffs",
            () -> new RestraintItem(RestraintType.LOCKED_CUFFS, new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("crime", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mcacrime"))
                    .icon(() -> new ItemStack(RESTRAINT_CUFFS.get()))
                    .displayItems((params, output) -> {
                        output.accept(RESTRAINT_ROPE.get());
                        output.accept(RESTRAINT_CUFFS.get());
                        output.accept(RESTRAINT_LOCKED_CUFFS.get());
                    })
                    .build());

    private CrimeItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        TABS.register(modBus);
    }

    /**
     * The restraint a stack represents: this mod's {@link RestraintItem} carries its own type; any other
     * {@code c:ropes}-tagged item counts as a {@link RestraintType#ROPE}; everything else is {@link
     * RestraintType#NONE} (not a restraint).
     */
    public static RestraintType restraintFor(ItemStack stack) {
        if (stack.getItem() instanceof RestraintItem restraint) {
            return restraint.getRestraintType();
        }
        if (stack.is(RestraintTags.ROPE)) {
            return RestraintType.ROPE;
        }
        return RestraintType.NONE;
    }

    /**
     * Sets aside the restraint a capture is about to spend, without spending it.
     *
     * <p>The consumption used to happen first and the capture second, so every refusal the custody
     * table could raise — already held, over the allowance — still cost the player their rope. Finding
     * the stack and taking it are separated here so the taking can wait until the capture stands.
     */
    public static Optional<RestraintReservation> reserveRestraint(ServerPlayer player, RestraintType type) {
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (!stack.isEmpty() && restraintFor(stack) == type) {
                return Optional.of(new RestraintReservation(i, stack.copy()));
            }
        }
        return Optional.empty();
    }

    /**
     * Spends a reservation, if the slot still holds what was reserved.
     *
     * <p>The identity check is not paranoia: the commit runs on the server thread but not in the same
     * instant the reservation was taken, and a player who swapped that slot in between must not have a
     * different stack shrunk on their behalf. Components, not counts — a differently enchanted pair of
     * cuffs is a different item however alike the two look in a slot.
     */
    public static boolean consumeReserved(ServerPlayer player, RestraintReservation reservation) {
        if (reservation == null || reservation.slot() < 0
                || reservation.slot() >= player.getInventory().items.size()) {
            return false;
        }
        ItemStack stack = player.getInventory().items.get(reservation.slot());
        if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, reservation.snapshot())) {
            return false;
        }
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.getInventory().setChanged();
        return true;
    }

    /** Whether the player is carrying anything that can cut a rope. */
    public static boolean hasCuttingTool(ServerPlayer player) {
        return carries(player, RestraintTags.CUTTING_TOOLS);
    }

    /** Whether the player is carrying anything that can open a lock. */
    public static boolean hasKey(ServerPlayer player) {
        return carries(player, RestraintTags.KEYS);
    }

    private static boolean carries(ServerPlayer player, net.minecraft.tags.TagKey<Item> tag) {
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.is(tag)) return true;
        }
        return !player.getOffhandItem().isEmpty() && player.getOffhandItem().is(tag);
    }

    public static RestraintType bestRestraint(ServerPlayer player) {
        RestraintType best = RestraintType.NONE;
        for (ItemStack stack : player.getInventory().items) {
            RestraintType type = restraintFor(stack);
            if (type.ordinal() > best.ordinal()) best = type;
        }
        return best;
    }
}
