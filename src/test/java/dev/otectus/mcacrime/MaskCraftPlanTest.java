package dev.otectus.mcacrime;

import dev.otectus.mcacrime.recipe.MaskCraftPlan;
import dev.otectus.mcacrime.recipe.MaskCraftRejection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic behind "one debit, one delivery", tested without a world.
 *
 * <p>Every case here is a way a Mask Station craft could go half-finished: enough clay but no string,
 * enough of both but nowhere to put the mask, a remainder with nowhere to go, an unbounded shift-click.
 * The plan's whole job is to answer those before anything is consumed, so these are the assertions
 * that make invariant 7 more than a comment.
 */
class MaskCraftPlanTest {

    /** The shipped clay mask: four clay, two string, no dye, no remainders. */
    private static MaskCraftPlan clay(int clayHeld, int stringHeld, int room, int batch) {
        return MaskCraftPlan.prepare(4, 2, 0, 0, clayHeld, stringHeld, 0, room, batch);
    }

    @Test
    void oneCraftCostsExactlyTheRecipeQuantities() {
        MaskCraftPlan plan = clay(4, 2, 64, 1);
        assertTrue(plan.craftable());
        assertEquals(1, plan.crafts());
        assertEquals(4, plan.materialCost());
        assertEquals(2, plan.bindingCost());
        assertEquals(0, plan.dyeCost());
        assertEquals(MaskCraftRejection.NONE, plan.rejection());
    }

    @Test
    void theScarcestInputDecidesTheBatch() {
        // 64 clay is sixteen masks; 10 string is only five.
        assertEquals(5, clay(64, 10, 64, MaskCraftPlan.MAX_BATCH).crafts());
        // and the other way round.
        assertEquals(4, clay(17, 64, 64, MaskCraftPlan.MAX_BATCH).crafts());
    }

    @Test
    void materialForLessThanOneCraftIsRefusedRatherThanPartlySpent() {
        MaskCraftPlan plan = clay(3, 64, 64, 1);
        assertFalse(plan.craftable());
        assertEquals(0, plan.crafts());
        assertEquals(0, plan.materialCost(), "a refused plan debits nothing at all");
        assertEquals(MaskCraftRejection.NOT_ENOUGH_MATERIAL, plan.rejection());
    }

    @Test
    void bindingAndDyeShortagesAreNamedSeparately() {
        assertEquals(MaskCraftRejection.NOT_ENOUGH_BINDING, clay(64, 1, 64, 1).rejection());
        assertEquals(MaskCraftRejection.NOT_ENOUGH_DYE,
                MaskCraftPlan.prepare(4, 2, 1, 0, 64, 64, 0, 64, 1).rejection());
    }

    @Test
    void aFullInventoryRefusesTheCraftInsteadOfConsumingIt() {
        MaskCraftPlan plan = clay(64, 64, 0, MaskCraftPlan.MAX_BATCH);
        assertFalse(plan.craftable());
        assertEquals(MaskCraftRejection.NO_ROOM, plan.rejection());
        assertEquals(0, plan.materialCost());
        assertEquals(0, plan.bindingCost());
    }

    @Test
    void remaindersCompeteForTheSameDestinations() {
        // Two free slots, and every craft needs one for the mask and one for a returned bucket.
        MaskCraftPlan plan = MaskCraftPlan.prepare(1, 1, 0, 1, 64, 64, 0, 2, MaskCraftPlan.MAX_BATCH);
        assertEquals(1, plan.crafts(), "the output and its remainder share one pool of slots");
        assertEquals(2, plan.crafts() * (1 + plan.remaindersPerCraft()));
        // Four slots is two whole crafts; three is still only one, because half a craft is none.
        assertEquals(2, MaskCraftPlan.prepare(1, 1, 0, 1, 64, 64, 0, 4, MaskCraftPlan.MAX_BATCH).crafts());
        assertEquals(1, MaskCraftPlan.prepare(1, 1, 0, 1, 64, 64, 0, 3, MaskCraftPlan.MAX_BATCH).crafts());
    }

    @Test
    void aRemainderWithNowhereToGoBlocksTheCraftEntirely() {
        MaskCraftPlan plan = MaskCraftPlan.prepare(1, 1, 0, 3, 64, 64, 0, 3, 1);
        assertFalse(plan.craftable());
        assertEquals(MaskCraftRejection.NO_ROOM, plan.rejection());
    }

    @Test
    void shiftClickIsBounded() {
        // Materials for 64 masks and room for all of them still stops at the batch cap.
        MaskCraftPlan plan = MaskCraftPlan.prepare(1, 1, 0, 0, 4096, 4096, 0, 4096, 10_000);
        assertEquals(MaskCraftPlan.MAX_BATCH, plan.crafts());
    }

    @Test
    void aRouteAskingForNothingGetsNothing() {
        assertFalse(clay(64, 64, 64, 0).craftable());
    }

    @Test
    void aNonpositiveCostIsAnInvalidRecipeRatherThanFreeMasks() {
        assertEquals(MaskCraftRejection.INVALID_RECIPE,
                MaskCraftPlan.prepare(0, 2, 0, 0, 64, 64, 0, 64, 1).rejection());
        assertEquals(MaskCraftRejection.INVALID_RECIPE,
                MaskCraftPlan.prepare(4, -1, 0, 0, 64, 64, 0, 64, 1).rejection());
        assertEquals(0, MaskCraftPlan.prepare(0, 0, 0, 0, 64, 64, 0, 64, 64).crafts());
    }
}
