package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.economy.Currency;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.item.CrimeItemTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * The COMMIT half of the theft transaction: turns a {@link TheftPlanner.TheftPlan} into an actual
 * loss, and reports exactly what the loss was (spec §"Establish transactional operations before
 * adding theft").
 *
 * <p>The one rule that matters here is that the stack this returns is the stack {@code removeItem}
 * gave back, by reference. Rebuilding it from the plan — same item, same count — would be the
 * duplication bug the spec names: a plan says "one of slot 14", and only the removal knows that slot
 * 14 held a renamed, enchanted sword.
 */
public final class TheftExecutor {

    /** Where the hotbar ends and ordinary storage begins. */
    private static final int HOTBAR_END = 9;
    /** The last ordinary storage slot; 36-39 are armor and 40 is the offhand. */
    private static final int MAIN_END = 36;

    /**
     * What was actually taken. Both fields empty/zero is a legitimate outcome — the victim had
     * nothing, or lost a race with their own currency between PREPARE and COMMIT.
     */
    public record TheftResult(@Nullable ItemStack stack, long currency) {

        public static TheftResult nothing() {
            return new TheftResult(null, 0L);
        }

        public boolean tookSomething() {
            return currency > 0L || (stack != null && !stack.isEmpty());
        }

        public boolean tookItem() {
            return stack != null && !stack.isEmpty();
        }
    }

    private TheftExecutor() {
    }

    /**
     * Reduces the victim's inventory to the flags the planner works in.
     *
     * <p>The whole container is walked rather than only slots 9-35, because which slots are protected
     * is a configuration question and the planner answers it from {@link TheftPlanner.SlotView#protectedSlot}.
     * A pack that turns {@code protectHotbar} off gets a thief who will go for the hotbar; the default
     * config leaves the spec's 9-35 window as the only eligible one.
     */
    public static TheftPlanner.InventorySnapshot snapshot(ServerPlayer victim, TheftPolicy policy) {
        if (victim == null || policy == null) {
            return TheftPlanner.InventorySnapshot.empty();
        }
        Inventory inventory = victim.getInventory();
        List<TheftPlanner.SlotView> slots = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue; // an empty slot is not worth a view; count 0 would be ignored anyway
            }
            slots.add(new TheftPlanner.SlotView(slot, stack.getCount(), isProtected(slot, policy),
                    stack.is(CrimeItemTags.THIEF_THEFT_IMMUNE)));
        }
        return new TheftPlanner.InventorySnapshot(slots);
    }

    /** Whether configuration puts this slot out of a thief's reach. */
    public static boolean isProtected(int slot, TheftPolicy policy) {
        if (slot < HOTBAR_END) {
            return policy.protectHotbar();
        }
        if (slot < MAIN_END) {
            return false;
        }
        return slot == Inventory.SLOT_OFFHAND ? policy.protectOffhand() : policy.protectArmor();
    }

    /**
     * Executes the plan against the victim.
     *
     * <p>Nothing here re-rolls. A currency debit takes what is there and reports the real figure, so a
     * victim who spent their emeralds between the plan and the commit loses what they still had rather
     * than going negative; an item removal returns the stack that left the inventory.
     */
    public static TheftResult commit(ServerPlayer victim, TheftPlanner.TheftPlan plan, Currency currency) {
        if (victim == null || plan == null) {
            return TheftResult.nothing();
        }
        if (plan instanceof TheftPlanner.CurrencyPlan currencyPlan) {
            if (currency == null || currencyPlan.amount() <= 0L) {
                return TheftResult.nothing();
            }
            long taken = Math.max(0L, currency.debit(victim, currencyPlan.amount(), TransactionReason.THEFT));
            return new TheftResult(null, taken);
        }
        if (plan instanceof TheftPlanner.ItemPlan itemPlan) {
            if (itemPlan.count() <= 0) {
                return TheftResult.nothing();
            }
            ItemStack removed = victim.getInventory().removeItem(itemPlan.slot(), itemPlan.count());
            if (removed == null || removed.isEmpty()) {
                return TheftResult.nothing();
            }
            victim.inventoryMenu.broadcastChanges();
            return new TheftResult(removed, 0L);
        }
        return TheftResult.nothing();
    }

    /** Plan and commit in one call, which is the only order these two are ever used in. */
    public static TheftResult steal(ServerPlayer victim, Currency currency, TheftPolicy policy,
                                    IntUnaryOperator rng) {
        if (victim == null || policy == null) {
            return TheftResult.nothing();
        }
        long balance = currency == null ? 0L : currency.balance(victim);
        TheftPlanner.TheftPlan plan = TheftPlanner.plan(balance, snapshot(victim, policy), policy, rng);
        return commit(victim, plan, currency);
    }
}
