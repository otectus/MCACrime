package dev.otectus.mcacrime.economy.fence;

import dev.otectus.mcacrime.economy.fence.FencePricing.PricingInputs;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Which of a fence's goods are on the counter today, and at what price.
 *
 * <p>Pure and deterministic per seed, which is what makes a fence's stock a fact about the world
 * rather than about how often the player opened the screen: the seed is the fence's identity plus its
 * restock day, so closing and reopening shows the same six trades and tomorrow shows different ones.
 *
 * <p>Ids in, ids out. Turning an id into an {@code ItemStack} — and dropping the ones no installed mod
 * registers — happens in {@code FenceMerchant}, so an optional mod being uninstalled costs a fence a
 * row rather than costing the server an exception.
 */
public final class FenceOfferBuilder {

    /**
     * One trade.
     *
     * @param playerSells true when the player hands the goods over and takes the price, false when the
     *                    fence is the one selling
     */
    public record FenceOffer(ResourceLocation item, long price, boolean playerSells) {
    }

    private FenceOfferBuilder() {
    }

    public static List<FenceOffer> build(long seed, int offerCount, Collection<FenceGood> goods,
                                         PricingInputs inputs, FencePolicy policy) {
        List<FenceOffer> offers = new ArrayList<>();
        if (goods == null || goods.isEmpty() || offerCount <= 0) {
            return offers;
        }
        // Sorted before shuffling: a Map's iteration order is not a promise, and the seed is only a
        // promise if what it shuffles arrived in the same order every time.
        List<FenceGood> pool = new ArrayList<>();
        for (FenceGood good : goods) {
            if (good != null && good.tradeable()) {
                pool.add(good);
            }
        }
        pool.sort((a, b) -> a.item().toString().compareTo(b.item().toString()));

        Random rng = new Random(seed);
        Collections.shuffle(pool, rng);

        int wanted = Math.min(offerCount, pool.size());
        for (int i = 0; i < wanted; i++) {
            FenceGood good = pool.get(i);
            boolean playerSells = good.buys() && (!good.sells() || rng.nextBoolean());
            long price = playerSells
                    ? FencePricing.buyPrice(good.basePrice(), inputs, policy)
                    : FencePricing.sellPrice(good.basePrice(), inputs, policy);
            offers.add(new FenceOffer(good.item(), price, playerSells));
        }
        return offers;
    }
}
