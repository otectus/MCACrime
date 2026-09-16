package dev.otectus.mcacrime.economy;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The inventory half of the item currency, against a real {@link Inventory} — when one can be had.
 *
 * <p>ModDevGradle's runner boots the game, so these run for real here. They still skip rather than
 * fail if the {@link Inventory} constructor cannot be had, because the arithmetic underneath is
 * covered unconditionally by {@code ItemCurrencyMathTest}; what is checked here is the part only a
 * real {@code ItemStack} can show — stack sizes that differ per item, and the rule that a stack with
 * data components on it is not money.
 */
class ItemCurrencyInventoryTest {

    private static boolean gameAvailable;

    @BeforeAll
    static void bootstrapGame() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            gameAvailable = true;
        } catch (Throwable unavailable) {
            gameAvailable = false;
        }
    }

    /** A bare inventory, or a skipped test. The player is only stored, never dereferenced here. */
    private static Inventory inventory() {
        assumeTrue(gameAvailable, "no bootstrapped Minecraft in this environment");
        Inventory inv;
        try {
            inv = new Inventory(null);
        } catch (Throwable needsAPlayer) {
            inv = null;
        }
        assumeTrue(inv != null, "Inventory requires a Player in this environment");
        return inv;
    }

    private static void put(Inventory inv, int slot, Item item, int count) {
        inv.items.set(slot, new ItemStack(item, count));
    }

    @Test
    void countsOnlyUnmodifiedStacksOfTheRightItem() {
        Inventory inv = inventory();
        put(inv, 0, Items.EMERALD, 12);
        put(inv, 1, Items.EMERALD, 30);
        put(inv, 2, Items.GOLD_NUGGET, 64);
        ItemStack named = new ItemStack(Items.EMERALD, 5);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Grandmother's emerald"));
        inv.items.set(3, named);

        assertEquals(42L, ItemStackCurrencySupport.count(inv, Items.EMERALD),
                "a named emerald is a keepsake, not small change");
        assertEquals(64L, ItemStackCurrencySupport.count(inv, Items.GOLD_NUGGET));
        assertEquals(0L, ItemStackCurrencySupport.count(inv, Items.ENDER_PEARL));
    }

    @Test
    void removeTakesWhatIsThereAndSaysHowMuch() {
        Inventory inv = inventory();
        put(inv, 0, Items.EMERALD, 12);
        put(inv, 1, Items.EMERALD, 30);

        assertEquals(20L, ItemStackCurrencySupport.remove(inv, Items.EMERALD, 20L));
        assertEquals(22L, ItemStackCurrencySupport.count(inv, Items.EMERALD));

        assertEquals(22L, ItemStackCurrencySupport.remove(inv, Items.EMERALD, 100L),
                "a partial debit takes everything and reports honestly");
        assertEquals(0L, ItemStackCurrencySupport.count(inv, Items.EMERALD));
    }

    @Test
    void removeOfNothingMovesNothing() {
        Inventory inv = inventory();
        put(inv, 0, Items.EMERALD, 12);
        assertEquals(0L, ItemStackCurrencySupport.remove(inv, Items.EMERALD, 0L));
        assertEquals(0L, ItemStackCurrencySupport.remove(inv, Items.EMERALD, -4L));
        assertEquals(12L, ItemStackCurrencySupport.count(inv, Items.EMERALD));
    }

    @Test
    void creditBoundedTopsUpPartialStacksBeforeUsingEmptyOnes() {
        Inventory inv = inventory();
        put(inv, 0, Items.EMERALD, 60);

        assertEquals(0L, ItemStackCurrencySupport.creditBounded(inv, Items.EMERALD, 4L));
        assertEquals(64, inv.items.get(0).getCount(), "the existing stack is filled first");
        assertTrue(inv.items.get(1).isEmpty(), "no new stack while an old one had room");

        assertEquals(0L, ItemStackCurrencySupport.creditBounded(inv, Items.EMERALD, 10L));
        assertEquals(64L + 10L, ItemStackCurrencySupport.count(inv, Items.EMERALD));
    }

    @Test
    void creditBoundedRespectsAnItemsOwnStackSize() {
        Inventory inv = inventory();
        assertEquals(0L, ItemStackCurrencySupport.creditBounded(inv, Items.ENDER_PEARL, 20L));
        assertEquals(16, inv.items.get(0).getCount(), "ender pearls stack to 16, not 64");
        assertEquals(4, inv.items.get(1).getCount());
    }

    @Test
    void creditBoundedReturnsTheRemainderRatherThanDroppingIt() {
        Inventory inv = inventory();
        for (int i = 0; i < inv.items.size(); i++) {
            put(inv, i, Items.STONE, 64); // full of something that is not money
        }
        assertEquals(50L, ItemStackCurrencySupport.creditBounded(inv, Items.EMERALD, 50L),
                "a bounty that will not fit is re-queued, never left on the floor");
        assertEquals(0L, ItemStackCurrencySupport.count(inv, Items.EMERALD));
    }

    @Test
    void toStacksSplitsByTheItemsOwnStackSize() {
        assumeTrue(gameAvailable, "no bootstrapped Minecraft in this environment");
        assertEquals(2, ItemStackCurrencySupport.toStacks(Items.EMERALD, 100L).size());
        assertEquals(2L, ItemStackCurrencySupport.stacksNeeded(Items.EMERALD, 100L));
        assertEquals(7L, ItemStackCurrencySupport.stacksNeeded(Items.ENDER_PEARL, 100L));
        assertEquals(0L, ItemStackCurrencySupport.stacksNeeded(Items.EMERALD, 0L));
    }
}
