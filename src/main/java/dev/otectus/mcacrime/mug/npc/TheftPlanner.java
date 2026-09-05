package dev.otectus.mcacrime.mug.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Decides what a completed mugging takes, and nothing else (spec §"Theft resolution").
 *
 * <p>This is the PREPARE half of the theft transaction: it produces a plan and never touches an
 * inventory or a balance. Everything it needs about the victim arrives as plain numbers, which is
 * what makes the currency matrix and the slot-eligibility matrix testable without a world — and, more
 * importantly, what makes "roll once" enforceable. The spec is explicit that the server must never
 * roll to decide what to remove and roll again to decide what to remember; a plan that is decided
 * here and executed verbatim cannot.
 *
 * <p>Priority is strict and the spec's: currency if there is any to take, else exactly one eligible
 * ordinary slot, else nothing. A thief who finds nothing still flees — that is the caller's problem,
 * not this class's.
 */
public final class TheftPlanner {

    /**
     * One inventory slot as the planner sees it: how much is in it and whether it is off limits.
     *
     * <p>Deliberately stack-free. The planner does not need to know what the item is — only the
     * executor does, and it is the executor's job to remove exactly the stack the plan names. Keeping
     * {@code ItemStack} out of here is also what lets the eligibility rules be tested at all, since an
     * item registry does not exist outside a running game.
     *
     * @param index        the real inventory slot index, which is what the executor will remove from
     * @param count        items in the slot; zero means empty
     * @param protectedSlot the slot is protected by configuration (hotbar, armor, offhand)
     * @param immuneTag    the item carries {@code mcacrime:thief_theft_immune}
     */
    public record SlotView(int index, int count, boolean protectedSlot, boolean immuneTag) {

        /** Eligible slots are non-empty, unprotected and untagged. All three are absolute. */
        public boolean eligible() {
            return count > 0 && !protectedSlot && !immuneTag;
        }
    }

    /** The victim's inventory, reduced to what theft cares about. */
    public record InventorySnapshot(List<SlotView> slots) {

        public InventorySnapshot {
            slots = slots == null ? List.of() : List.copyOf(slots);
        }

        public static InventorySnapshot empty() {
            return new InventorySnapshot(List.of());
        }
    }

    /** What the executor is to do. Exactly one of the three, decided once. */
    public sealed interface TheftPlan permits CurrencyPlan, ItemPlan, NothingPlan {

        /** True when this plan will move something, which is what the outcome message keys on. */
        default boolean takesSomething() {
            return !(this instanceof NothingPlan);
        }
    }

    /** Take {@code amount} of the active currency. The executor may find less and takes what is there. */
    public record CurrencyPlan(long amount) implements TheftPlan {
    }

    /** Take {@code count} items out of slot {@code slot}. */
    public record ItemPlan(int slot, int count) implements TheftPlan {
    }

    /** The victim had nothing worth taking. */
    public record NothingPlan() implements TheftPlan {

        private static final NothingPlan INSTANCE = new NothingPlan();
    }

    private TheftPlanner() {
    }

    /** The single instance of "nothing", so callers can compare cheaply. */
    public static TheftPlan nothing() {
        return NothingPlan.INSTANCE;
    }

    /**
     * Plans one theft.
     *
     * @param currencyBalance what the victim holds of the active currency; negative is treated as zero
     * @param rng             {@code bound -> value in [0, bound)}, the shape of
     *                        {@code RandomSource::nextInt}; never called with a bound below 1
     */
    public static TheftPlan plan(long currencyBalance, InventorySnapshot inv, TheftPolicy policy,
                                 IntUnaryOperator rng) {
        if (policy == null || rng == null) {
            return nothing();
        }
        long currency = planCurrency(currencyBalance, policy, rng);
        if (currency > 0L) {
            return new CurrencyPlan(currency);
        }
        return planItem(inv, policy, rng);
    }

    /**
     * The spec's formula: {@code maxStealable = min(balance, configuredMax)} and
     * {@code amount = randomBetween(min(configuredMin, maxStealable), maxStealable)}.
     *
     * <p>{@code stealAllIfBelowMinimum} decides what happens when the victim holds less than the
     * configured minimum. On, the formula above applies and the thief cleans them out; off, currency
     * theft is refused entirely and the fallback item path runs instead — a thief who will not take
     * four emeralds does not take four emeralds, they take a spare pickaxe.
     *
     * @return 0 when no currency is to be taken, never a negative number
     */
    private static long planCurrency(long balance, TheftPolicy policy, IntUnaryOperator rng) {
        long available = Math.max(0L, balance);
        long maxStealable = Math.min(available, policy.maxCurrencySteal());
        if (maxStealable <= 0L) {
            return 0L;
        }
        if (maxStealable < policy.minCurrencySteal() && !policy.stealAllIfBelowMinimum()) {
            return 0L;
        }
        long low = Math.min(policy.minCurrencySteal(), maxStealable);
        long span = maxStealable - low + 1L;
        // The configured maximum is bounded well inside int range, and maxStealable is bounded by it.
        long roll = span <= 1L ? 0L : rng.applyAsInt((int) span);
        return Math.max(0L, Math.min(maxStealable, low + roll));
    }

    /** Exactly one count from exactly one eligible slot, chosen uniformly among the eligible ones. */
    private static TheftPlan planItem(InventorySnapshot inv, TheftPolicy policy, IntUnaryOperator rng) {
        if (inv == null || inv.slots().isEmpty()) {
            return nothing();
        }
        List<SlotView> eligible = new ArrayList<>();
        for (SlotView slot : inv.slots()) {
            if (slot != null && slot.eligible()) {
                eligible.add(slot);
            }
        }
        if (eligible.isEmpty()) {
            return nothing();
        }
        SlotView chosen = eligible.size() == 1 ? eligible.get(0) : eligible.get(rng.applyAsInt(eligible.size()));
        return new ItemPlan(chosen.index(), countFor(chosen, policy, rng));
    }

    private static int countFor(SlotView slot, TheftPolicy policy, IntUnaryOperator rng) {
        return switch (policy.mode()) {
            case WHOLE_STACK -> slot.count();
            case RANDOM_COUNT -> 1 + (slot.count() <= 1 ? 0 : rng.applyAsInt(slot.count()));
            case SINGLE_ITEM -> 1;
        };
    }
}
