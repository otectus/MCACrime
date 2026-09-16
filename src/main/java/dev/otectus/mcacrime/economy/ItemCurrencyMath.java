package dev.otectus.mcacrime.economy;

/**
 * The arithmetic behind an item-backed currency, with no Minecraft type anywhere in it (0.7.1).
 *
 * <p>Split out rather than written inline for one reason: the interesting bugs in "pay somebody 900
 * gold nuggets" are off-by-ones at stack boundaries, and those are only cheap to test when the test
 * does not need a bootstrapped registry to construct an {@code ItemStack}. Everything here is a pure
 * function of counts, so {@code ItemCurrencyMathTest} runs in microseconds and covers the cases a
 * live inventory test never would.
 *
 * <p>Every method treats a non-positive amount and a {@code maxStack} below 1 as "nothing to do",
 * because both arrive from third-party data — a config-named item, an economy mod's stack size — and
 * neither is worth an exception in the middle of paying a fine.
 */
public final class ItemCurrencyMath {

    private ItemCurrencyMath() {
    }

    /** How many stacks of {@code maxStack} it takes to hold {@code amount}; 0 for nothing. */
    public static long stacksNeeded(long amount, int maxStack) {
        if (amount <= 0L || maxStack < 1) {
            return 0L;
        }
        return (amount + maxStack - 1L) / maxStack;
    }

    /**
     * The stack sizes {@code amount} splits into: full stacks first, then whatever is left over.
     *
     * <p>Returns an empty array for nothing to split. An amount large enough to need more than
     * {@link Integer#MAX_VALUE} stacks is not representable as an array, and is refused the same way
     * as zero rather than attempting an allocation that would take the server down.
     */
    public static int[] splitCounts(long amount, int maxStack) {
        long stacks = stacksNeeded(amount, maxStack);
        if (stacks <= 0L || stacks > Integer.MAX_VALUE) {
            return new int[0];
        }
        int[] counts = new int[(int) stacks];
        long remaining = amount;
        for (int i = 0; i < counts.length; i++) {
            counts[i] = (int) Math.min(remaining, maxStack);
            remaining -= counts[i];
        }
        return counts;
    }

    /**
     * How much of {@code amount} would <em>not</em> fit, given the free capacity of each candidate slot.
     *
     * <p>A slot whose free capacity equals {@code maxStack} is an empty slot; anything smaller is a
     * partial stack of the currency item. Partial stacks are filled first and empties only afterwards,
     * which is the order the bounty payout walk has always used: topping up a stack the player already
     * has leaves the inventory tidier than scattering new stacks through every gap.
     *
     * @param freeCapacityPerSlot free capacity per slot; null, negative and zero entries are skipped
     * @return the undelivered remainder, 0 when everything fits
     */
    public static long planCredit(int[] freeCapacityPerSlot, long amount, int maxStack) {
        if (amount <= 0L || maxStack < 1) {
            return 0L;
        }
        if (freeCapacityPerSlot == null) {
            return amount;
        }
        long left = amount;
        // Pass one: partial stacks of the currency item, cheapest place to put coins.
        for (int capacity : freeCapacityPerSlot) {
            if (left <= 0L) {
                break;
            }
            if (capacity > 0 && capacity < maxStack) {
                left -= Math.min(capacity, left);
            }
        }
        // Pass two: empty slots.
        for (int capacity : freeCapacityPerSlot) {
            if (left <= 0L) {
                break;
            }
            if (capacity >= maxStack) {
                left -= Math.min(maxStack, left);
            }
        }
        return Math.max(0L, left);
    }
}
