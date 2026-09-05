package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.fence.FenceGood;
import dev.otectus.mcacrime.economy.fence.FenceGoodsRegistry;
import dev.otectus.mcacrime.economy.fence.FenceOfferBuilder;
import dev.otectus.mcacrime.economy.fence.FenceOfferBuilder.FenceOffer;
import dev.otectus.mcacrime.economy.fence.FencePolicy;
import dev.otectus.mcacrime.economy.fence.FencePricing;
import dev.otectus.mcacrime.economy.fence.FencePricing.PricingInputs;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is on a fence's counter today (0.5.1, spec section "Fence behavior and inventory").
 *
 * <p>Determinism is the property that matters: the seed is the fence's identity plus its restock day,
 * so a player who closes and reopens the screen sees the same trades. Without that, the restock
 * interval is decorative and a fence is a slot machine.
 */
class FenceOfferBuilderTest {

    private static final FencePolicy POLICY = FencePolicy.defaults();
    private static final PricingInputs NEUTRAL = new PricingInputs(0L, 0L, false, -100L, 50L);
    private static final ResourceLocation TNT = new ResourceLocation("minecraft", "tnt");

    private static FenceGoodsRegistry stocked() {
        FenceGoodsRegistry registry = new FenceGoodsRegistry();
        registry.contribute(TNT, 24L, true, true);
        registry.contribute(new ResourceLocation("minecraft", "gunpowder"), 4L, false, true);
        registry.contribute(new ResourceLocation("minecraft", "ender_pearl"), 12L, true, false);
        registry.contribute(new ResourceLocation("mcacrime", "restraint_rope"), 6L, true, true);
        registry.contribute(new ResourceLocation("mcacrime", "restraint_cuffs"), 12L, true, true);
        registry.contribute(new ResourceLocation("locks", "iron_lock_pick"), 6L, true, false);
        return registry;
    }

    private static List<FenceOffer> build(long seed, int count, FenceGoodsRegistry registry) {
        return FenceOfferBuilder.build(seed, count, registry.tradeable(), NEUTRAL, POLICY);
    }

    @Test
    void theSameSeedBuildsTheSameCounter() {
        assertEquals(build(4242L, 4, stocked()), build(4242L, 4, stocked()),
                "reopening the screen must not reroll the stock");
    }

    @Test
    void aDifferentRestockDayBuildsADifferentCounter() {
        boolean anyDifference = false;
        for (long seed = 1L; seed <= 20L && !anyDifference; seed++) {
            anyDifference = !build(0L, 3, stocked()).equals(build(seed, 3, stocked()));
        }
        assertTrue(anyDifference, "every seed produced identical stock, so restocking changes nothing");
    }

    @Test
    void theOfferCountIsRespectedInBothDirections() {
        assertEquals(3, build(7L, 3, stocked()).size());
        assertEquals(6, build(7L, 99, stocked()).size(), "a fence cannot offer more than it stocks");
        assertTrue(build(7L, 0, stocked()).isEmpty());
    }

    @Test
    void theBlacklistWinsOverEverythingOnTheCounter() {
        FenceGoodsRegistry registry = stocked();
        registry.blacklist(TNT);
        for (FenceOffer offer : build(11L, 99, registry)) {
            assertNotEquals(TNT, offer.item(), "a blacklisted item reached the counter anyway");
        }
        assertEquals(5, build(11L, 99, registry).size());
    }

    @Test
    void anItemNoDirectionAllowsIsNeverOffered() {
        FenceGoodsRegistry registry = new FenceGoodsRegistry();
        registry.contribute(TNT, 24L, false, false);
        assertTrue(build(3L, 6, registry).isEmpty());
    }

    @Test
    void everyOfferIsPricedInTheDirectionItTravels() {
        FenceGoodsRegistry registry = stocked();
        for (FenceOffer offer : build(99L, 99, registry)) {
            FenceGood good = registry.snapshot().get(offer.item());
            long expected = offer.playerSells()
                    ? FencePricing.buyPrice(good.basePrice(), NEUTRAL, POLICY)
                    : FencePricing.sellPrice(good.basePrice(), NEUTRAL, POLICY);
            assertEquals(expected, offer.price(), "wrong side of the counter for " + offer.item());
            if (offer.playerSells()) {
                assertTrue(good.buys(), offer.item() + " was bought from the player but is not a buy line");
            } else {
                assertTrue(good.sells(), offer.item() + " was sold to the player but is not a sell line");
            }
        }
    }

    @Test
    void nothingIsBuiltFromAnEmptyBook() {
        assertTrue(FenceOfferBuilder.build(1L, 6, List.of(), NEUTRAL, POLICY).isEmpty());
    }
}
