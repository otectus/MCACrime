package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.ItemCurrencyMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The stack arithmetic behind an item-backed currency, tested without a single {@code ItemStack}.
 *
 * <p>The bugs worth catching here are all boundaries: an exact multiple of the stack size must not
 * need an extra stack, a 16-stack item must not be paid out in 64s, and a credit that exactly fills
 * the inventory must report a remainder of zero rather than one.
 */
class ItemCurrencyMathTest {

    @Test
    void stacksNeededRoundsUpButNotPastAnExactMultiple() {
        assertEquals(1L, ItemCurrencyMath.stacksNeeded(1L, 64));
        assertEquals(1L, ItemCurrencyMath.stacksNeeded(64L, 64), "an exact stack is one stack");
        assertEquals(2L, ItemCurrencyMath.stacksNeeded(65L, 64));
        assertEquals(4L, ItemCurrencyMath.stacksNeeded(64L, 16), "a 16-stack item needs four");
        assertEquals(70L, ItemCurrencyMath.stacksNeeded(70L, 1), "an unstackable coin is one per stack");
    }

    @Test
    void nothingToPayNeedsNoStacks() {
        assertEquals(0L, ItemCurrencyMath.stacksNeeded(0L, 64));
        assertEquals(0L, ItemCurrencyMath.stacksNeeded(-5L, 64));
        assertEquals(0L, ItemCurrencyMath.stacksNeeded(10L, 0), "a nonsense stack size is not a crash");
    }

    @Test
    void splitCountsFillsStacksBeforeTheRemainder() {
        assertArrayEquals(new int[]{64, 64, 12}, ItemCurrencyMath.splitCounts(140L, 64));
        assertArrayEquals(new int[]{64, 64}, ItemCurrencyMath.splitCounts(128L, 64));
        assertArrayEquals(new int[]{16, 16, 3}, ItemCurrencyMath.splitCounts(35L, 16));
        assertArrayEquals(new int[]{1, 1, 1}, ItemCurrencyMath.splitCounts(3L, 1));
    }

    @Test
    void splitCountsOfNothingIsNothing() {
        assertArrayEquals(new int[0], ItemCurrencyMath.splitCounts(0L, 64));
        assertArrayEquals(new int[0], ItemCurrencyMath.splitCounts(-3L, 64));
        assertArrayEquals(new int[0], ItemCurrencyMath.splitCounts(10L, 0));
    }

    @Test
    void planCreditFillsPartialStacksBeforeEmptyOnes() {
        // One empty slot (64 free) and one stack of 60 emeralds (4 free): 4 fit in the partial stack.
        assertEquals(0L, ItemCurrencyMath.planCredit(new int[]{64, 4}, 4L, 64));
        // 68 needs the partial stack's 4 and then 64 of the empty slot: exactly full, nothing left.
        assertEquals(0L, ItemCurrencyMath.planCredit(new int[]{64, 4}, 68L, 64));
        assertEquals(1L, ItemCurrencyMath.planCredit(new int[]{64, 4}, 69L, 64));
    }

    @Test
    void planCreditReportsWhatDoesNotFit() {
        assertEquals(100L, ItemCurrencyMath.planCredit(new int[0], 100L, 64), "no space is all remainder");
        assertEquals(100L, ItemCurrencyMath.planCredit(null, 100L, 64));
        assertEquals(36L, ItemCurrencyMath.planCredit(new int[]{64}, 100L, 64));
        assertEquals(0L, ItemCurrencyMath.planCredit(new int[]{64, 64, 64}, 192L, 64));
    }

    @Test
    void planCreditOfNothingOwesNothing() {
        assertEquals(0L, ItemCurrencyMath.planCredit(new int[0], 0L, 64));
        assertEquals(0L, ItemCurrencyMath.planCredit(null, -7L, 64));
        assertEquals(0L, ItemCurrencyMath.planCredit(new int[]{64}, 10L, 0));
    }

    @Test
    void emptyAndNegativeCapacitiesAreIgnoredRatherThanCounted() {
        assertEquals(10L, ItemCurrencyMath.planCredit(new int[]{0, -4}, 10L, 64));
    }
}
