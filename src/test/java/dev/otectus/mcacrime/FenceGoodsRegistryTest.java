package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.fence.FenceGood;
import dev.otectus.mcacrime.economy.fence.FenceGoodsRegistry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merge rules behind a fence's book (0.5.1, spec section "Fence behavior and inventory").
 *
 * <p>Three sources contribute to one list -- tags, the price file, and optional-mod providers -- and
 * the rules for what happens when two of them name the same item are the whole reason this part is
 * separable from the registry lookups that fill it. Keyed by id rather than by {@code Item}, so the
 * rules can be asserted without a bootstrapped game.
 */
class FenceGoodsRegistryTest {

    private static final ResourceLocation TNT = new ResourceLocation("minecraft", "tnt");
    private static final ResourceLocation PICK = new ResourceLocation("locks", "iron_lock_pick");
    private static final ResourceLocation ROPE = new ResourceLocation("mcacrime", "restraint_rope");

    @Test
    void aProviderContributionMergesWithTagData() {
        FenceGoodsRegistry registry = new FenceGoodsRegistry();
        registry.contribute(TNT, 8L, true, false);   // the tag, at the default price
        registry.contribute(TNT, 24L, false, true);  // the price file, adding the other direction

        FenceGood tnt = registry.snapshot().get(TNT);
        assertEquals(24L, tnt.basePrice(), "the last price stated wins, so a price file may retune a tag");
        assertTrue(tnt.sells());
        assertTrue(tnt.buys());
    }

    @Test
    void reContributingTheSameEntryChangesNothing() {
        FenceGoodsRegistry registry = new FenceGoodsRegistry();
        registry.contribute(PICK, 6L, true, true);
        Map<ResourceLocation, FenceGood> once = registry.snapshot();
        registry.contribute(PICK, 6L, true, true);

        assertEquals(once, registry.snapshot(),
                "a provider called twice by two reloads must not double a fence's stock");
        assertEquals(1, registry.size());
    }

    @Test
    void theBlacklistBeatsEverySource() {
        FenceGoodsRegistry registry = new FenceGoodsRegistry();
        registry.contribute(TNT, 24L, true, true);
        registry.contribute(ROPE, 6L, true, true);
        registry.blacklist(TNT);

        assertTrue(registry.isBlacklisted(TNT));
        assertFalse(registry.snapshot().containsKey(TNT),
                "an owner banning an item must not have to find which source added it");
        assertTrue(registry.snapshot().containsKey(ROPE));
    }

    @Test
    void anItemThatTradesInNoDirectionIsNotStock() {
        FenceGoodsRegistry registry = new FenceGoodsRegistry();
        registry.contribute(TNT, 24L, false, false);
        assertEquals(0, registry.size());
    }

    @Test
    void aPriceBelowOneIsRaisedRatherThanSoldForNothing() {
        FenceGoodsRegistry registry = new FenceGoodsRegistry();
        registry.contribute(ROPE, 0L, true, true);
        assertEquals(1L, registry.snapshot().get(ROPE).basePrice());
    }

    @Test
    void theTradeableListIsOrderedTheSameWayEveryTime() {
        FenceGoodsRegistry first = new FenceGoodsRegistry();
        first.contribute(TNT, 24L, true, true);
        first.contribute(PICK, 6L, true, true);
        FenceGoodsRegistry second = new FenceGoodsRegistry();
        second.contribute(PICK, 6L, true, true);
        second.contribute(TNT, 24L, true, true);

        assertEquals(first.tradeable(), second.tradeable(),
                "the offer seed is only a promise if what it shuffles arrives in a fixed order");
    }
}
