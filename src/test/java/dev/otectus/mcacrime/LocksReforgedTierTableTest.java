package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.locksreforged.LocksReforgedCompat;
import dev.otectus.mcacrime.compat.locksreforged.LocksReforgedCompat.LocksGood;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Locks Reforged price table (0.5.1, spec section "Locks Reforged loading").
 *
 * <p>Pure by construction: the table is strings and numbers, so this asserts it without a registry,
 * without the mod, and without a game. That is the same property that lets the adapter load on a
 * server which has never heard of Locks Reforged.
 *
 * <p>Two things are worth holding it to. The table must be total over the ids that mod ships, because
 * a missing entry is stock a fence silently never offers rather than an error anybody sees. And a
 * pick must cost less than the lock it defeats, or the criminal economy is telling the player to buy
 * the lock instead.
 */
class LocksReforgedTierTableTest {

    /** Every item id Locks Reforged registers that a fence might deal in. */
    private static final List<String> KNOWN_IDS = List.of(
            "wood_lock", "copper_lock", "iron_lock", "steel_lock", "gold_lock", "diamond_lock",
            "netherite_lock",
            "wood_lock_pick", "copper_lock_pick", "iron_lock_pick", "steel_lock_pick", "gold_lock_pick",
            "diamond_lock_pick", "netherite_lock_pick",
            "key", "master_key", "key_blank", "key_ring");

    private static Map<String, Long> table() {
        Map<String, Long> prices = new LinkedHashMap<>();
        for (LocksGood good : LocksReforgedCompat.tierTable()) {
            prices.put(good.id(), good.basePrice());
        }
        return prices;
    }

    @Test
    void theTableIsTotalOverEveryKnownId() {
        Map<String, Long> prices = table();
        Set<String> missing = new TreeSet<>();
        for (String id : KNOWN_IDS) {
            if (!prices.containsKey(id)) {
                missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(), "these Locks Reforged goods have no price, so no fence ever "
                + "offers them: " + missing);
    }

    @Test
    void everyPriceIsPositive() {
        Set<String> free = new TreeSet<>();
        table().forEach((id, price) -> {
            if (price < 1L) {
                free.add(id);
            }
        });
        assertTrue(free.isEmpty(), "a fence cannot deal in something worth nothing: " + free);
    }

    @Test
    void aPickCostsLessThanTheLockItDefeats() {
        Map<String, Long> prices = table();
        for (String tier : List.of("wood", "copper", "iron", "steel", "gold", "diamond", "netherite")) {
            long lock = prices.get(tier + "_lock");
            long pick = prices.get(tier + "_lock_pick");
            assertTrue(pick < lock, tier + " picks cost " + pick + " against a lock at " + lock
                    + "; a pick dearer than the lock tells the player to buy the lock instead");
        }
    }

    @Test
    void securityIsPricedByTier() {
        Map<String, Long> prices = table();
        List<String> ascending = List.of("wood", "copper", "iron", "steel", "gold", "diamond", "netherite");
        for (int i = 1; i < ascending.size(); i++) {
            long cheaper = prices.get(ascending.get(i - 1) + "_lock");
            long dearer = prices.get(ascending.get(i) + "_lock");
            assertTrue(dearer > cheaper, ascending.get(i) + " locks are not dearer than "
                    + ascending.get(i - 1) + " ones, so tier means nothing to the price");
        }
    }
}
