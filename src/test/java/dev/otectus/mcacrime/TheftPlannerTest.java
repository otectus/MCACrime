package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.TheftPlanner;
import dev.otectus.mcacrime.mug.npc.TheftPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a mugging takes, over the spec's two matrices.
 *
 * <p>The rolls are pinned rather than seeded: {@link #LOWEST} and {@link #HIGHEST} stand in for the
 * two ends of every random range, so each assertion below states a bound rather than a sample. That
 * is also why the planner takes an {@code IntUnaryOperator} at all — a test that has to construct a
 * {@code RandomSource} is a test that needs a game.
 */
class TheftPlannerTest {

    /** Always the bottom of the range. */
    private static final IntUnaryOperator LOWEST = bound -> 0;
    /** Always the top of the range. */
    private static final IntUnaryOperator HIGHEST = bound -> bound - 1;

    private static TheftPolicy policy(long min, long max, boolean stealAllIfBelowMinimum) {
        return new TheftPolicy(min, max, stealAllIfBelowMinimum, true, true, true,
                TheftPolicy.ItemTheftMode.SINGLE_ITEM);
    }

    private static TheftPolicy policy(TheftPolicy.ItemTheftMode mode) {
        return new TheftPolicy(1L, 8L, true, true, true, true, mode);
    }

    /** An ordinary storage slot with something in it. */
    private static TheftPlanner.SlotView ordinary(int index, int count) {
        return new TheftPlanner.SlotView(index, count, false, false);
    }

    private static TheftPlanner.InventorySnapshot inventory(TheftPlanner.SlotView... slots) {
        return new TheftPlanner.InventorySnapshot(List.of(slots));
    }

    // ------------------------------------------------------------------ currency

    @Test
    void noCurrencyFallsThroughToTheItem() {
        TheftPlanner.TheftPlan plan = TheftPlanner.plan(0L, inventory(ordinary(14, 3)),
                policy(1L, 8L, true), LOWEST);
        assertEquals(new TheftPlanner.ItemPlan(14, 1), plan);
    }

    @Test
    void oneCurrencyIsTakenWhole() {
        assertEquals(new TheftPlanner.CurrencyPlan(1L),
                TheftPlanner.plan(1L, inventory(), policy(1L, 8L, true), HIGHEST));
    }

    @Test
    void belowTheMinimumTakesEverythingWhenConfiguredTo() {
        // balance 3, minimum 5: maxStealable is 3, so the range collapses onto it.
        assertEquals(new TheftPlanner.CurrencyPlan(3L),
                TheftPlanner.plan(3L, inventory(), policy(5L, 8L, true), LOWEST));
        assertEquals(new TheftPlanner.CurrencyPlan(3L),
                TheftPlanner.plan(3L, inventory(), policy(5L, 8L, true), HIGHEST));
    }

    @Test
    void belowTheMinimumFallsThroughToTheItemWhenNotConfiguredTo() {
        TheftPlanner.TheftPlan plan = TheftPlanner.plan(3L, inventory(ordinary(20, 64)),
                policy(5L, 8L, false), LOWEST);
        assertEquals(new TheftPlanner.ItemPlan(20, 1), plan);
    }

    @Test
    void belowTheMinimumWithNothingElseToTakeIsNothing() {
        assertInstanceOf(TheftPlanner.NothingPlan.class,
                TheftPlanner.plan(3L, inventory(), policy(5L, 8L, false), LOWEST));
    }

    @Test
    void exactlyTheMaximumStaysInsideTheConfiguredRange() {
        assertEquals(new TheftPlanner.CurrencyPlan(1L),
                TheftPlanner.plan(8L, inventory(), policy(1L, 8L, true), LOWEST));
        assertEquals(new TheftPlanner.CurrencyPlan(8L),
                TheftPlanner.plan(8L, inventory(), policy(1L, 8L, true), HIGHEST));
    }

    @Test
    void aRichVictimStillLosesNoMoreThanTheMaximum() {
        assertEquals(new TheftPlanner.CurrencyPlan(8L),
                TheftPlanner.plan(1_000_000L, inventory(), policy(1L, 8L, true), HIGHEST));
    }

    @Test
    void noRollEverProducesANegativeOrExcessiveAmount() {
        for (long balance = 0L; balance <= 20L; balance++) {
            for (IntUnaryOperator rng : List.of(LOWEST, HIGHEST)) {
                TheftPlanner.TheftPlan plan = TheftPlanner.plan(balance, inventory(),
                        policy(3L, 7L, true), rng);
                if (plan instanceof TheftPlanner.CurrencyPlan currency) {
                    assertTrue(currency.amount() > 0L, "a currency plan that takes nothing is not a plan");
                    assertTrue(currency.amount() <= balance, "took more than the victim had");
                    assertTrue(currency.amount() <= 7L, "took more than the configured maximum");
                }
            }
        }
    }

    @Test
    void aNegativeBalanceIsTreatedAsEmpty() {
        assertInstanceOf(TheftPlanner.NothingPlan.class,
                TheftPlanner.plan(-50L, inventory(), policy(1L, 8L, true), LOWEST));
    }

    @Test
    void aMaximumOfZeroDisablesCurrencyTheftEntirely() {
        assertEquals(new TheftPlanner.ItemPlan(11, 1),
                TheftPlanner.plan(64L, inventory(ordinary(11, 2)), policy(0L, 0L, true), LOWEST));
    }

    // ------------------------------------------------------------------ item fallback

    @Test
    void protectedSlotsAreNeverTouched() {
        // Hotbar only, armor only, offhand only: the same answer three times.
        assertInstanceOf(TheftPlanner.NothingPlan.class, TheftPlanner.plan(0L,
                inventory(new TheftPlanner.SlotView(3, 64, true, false)), policy(1L, 8L, true), LOWEST));
        assertInstanceOf(TheftPlanner.NothingPlan.class, TheftPlanner.plan(0L,
                inventory(new TheftPlanner.SlotView(38, 1, true, false)), policy(1L, 8L, true), LOWEST));
        assertInstanceOf(TheftPlanner.NothingPlan.class, TheftPlanner.plan(0L,
                inventory(new TheftPlanner.SlotView(40, 1, true, false)), policy(1L, 8L, true), LOWEST));
    }

    @Test
    void oneOrdinarySlotLosesExactlyOneCount() {
        assertEquals(new TheftPlanner.ItemPlan(9, 1),
                TheftPlanner.plan(0L, inventory(ordinary(9, 37)), policy(1L, 8L, true), HIGHEST));
    }

    @Test
    void severalOrdinarySlotsLoseExactlyOneOfThem() {
        TheftPlanner.InventorySnapshot inv = inventory(ordinary(9, 5), ordinary(17, 5), ordinary(35, 5));
        assertEquals(new TheftPlanner.ItemPlan(9, 1),
                TheftPlanner.plan(0L, inv, policy(1L, 8L, true), LOWEST));
        assertEquals(new TheftPlanner.ItemPlan(35, 1),
                TheftPlanner.plan(0L, inv, policy(1L, 8L, true), HIGHEST));
    }

    @Test
    void protectedSlotsAreSkippedOverRatherThanCounted() {
        // The chosen index must be a real inventory slot, not a position in the eligible sub-list.
        TheftPlanner.InventorySnapshot inv = inventory(
                new TheftPlanner.SlotView(0, 64, true, false),
                new TheftPlanner.SlotView(1, 64, true, false),
                ordinary(22, 1));
        assertEquals(new TheftPlanner.ItemPlan(22, 1),
                TheftPlanner.plan(0L, inv, policy(1L, 8L, true), HIGHEST));
    }

    @Test
    void anInventoryEntirelyProtectedByTagLosesNothing() {
        TheftPlanner.InventorySnapshot inv = inventory(
                new TheftPlanner.SlotView(10, 1, false, true),
                new TheftPlanner.SlotView(11, 64, false, true));
        assertInstanceOf(TheftPlanner.NothingPlan.class,
                TheftPlanner.plan(0L, inv, policy(1L, 8L, true), LOWEST));
    }

    @Test
    void anEmptySlotIsNotAnEligibleSlot() {
        assertInstanceOf(TheftPlanner.NothingPlan.class,
                TheftPlanner.plan(0L, inventory(ordinary(12, 0)), policy(1L, 8L, true), LOWEST));
    }

    @Test
    void wholeStackModeTakesTheSlot() {
        assertEquals(new TheftPlanner.ItemPlan(14, 37),
                TheftPlanner.plan(0L, inventory(ordinary(14, 37)),
                        policy(TheftPolicy.ItemTheftMode.WHOLE_STACK), LOWEST));
    }

    @Test
    void randomCountModeStaysBetweenOneAndTheStack() {
        TheftPlanner.InventorySnapshot inv = inventory(ordinary(14, 37));
        assertEquals(new TheftPlanner.ItemPlan(14, 1),
                TheftPlanner.plan(0L, inv, policy(TheftPolicy.ItemTheftMode.RANDOM_COUNT), LOWEST));
        assertEquals(new TheftPlanner.ItemPlan(14, 37),
                TheftPlanner.plan(0L, inv, policy(TheftPolicy.ItemTheftMode.RANDOM_COUNT), HIGHEST));
    }

    @Test
    void currencyStillOutranksAnItemInEveryMode() {
        for (TheftPolicy.ItemTheftMode mode : TheftPolicy.ItemTheftMode.values()) {
            assertInstanceOf(TheftPlanner.CurrencyPlan.class,
                    TheftPlanner.plan(64L, inventory(ordinary(14, 37)), policy(mode), LOWEST),
                    "mode " + mode + " changed the currency-first priority");
        }
    }

    @Test
    void aMinimumAboveTheMaximumIsClampedRatherThanInverted() {
        TheftPolicy clamped = policy(50L, 8L, true);
        assertEquals(8L, clamped.minCurrencySteal());
        assertEquals(new TheftPlanner.CurrencyPlan(8L),
                TheftPlanner.plan(64L, inventory(), clamped, LOWEST));
    }
}
