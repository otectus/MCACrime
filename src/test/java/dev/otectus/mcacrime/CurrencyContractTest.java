package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.Currency;
import dev.otectus.mcacrime.economy.ItemCurrency;
import dev.otectus.mcacrime.economy.TransactionReason;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract an economy mod has to satisfy, exercised against a currency that is nothing but a
 * number.
 *
 * <p>No {@code ServerPlayer} is constructible here and none is needed: the two rules under test are
 * about arithmetic, not about inventories. {@code debit} is partial and honest about how much it
 * took; {@code tryCharge}, which is a default written on top of it, is all-or-nothing. The emerald
 * implementation is the same two rules over an inventory, and the fake is what proves the default
 * body itself is right for everybody who does not override it.
 */
class CurrencyContractTest {

    /** A balance in a field. Ignores the player entirely, which is exactly what makes it testable. */
    private static final class FakeCurrency implements Currency {

        private static final ResourceLocation ID = new ResourceLocation("mcacrime", "test_coin");

        private long balance;

        FakeCurrency(long balance) {
            this.balance = balance;
        }

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public long balance(ServerPlayer player) {
            return balance;
        }

        @Override
        public long debit(ServerPlayer player, long requested, TransactionReason reason) {
            if (requested <= 0L) {
                return 0L;
            }
            long taken = Math.min(requested, balance);
            balance -= taken;
            return taken;
        }

        @Override
        public void credit(ServerPlayer player, long amount, TransactionReason reason) {
            if (amount > 0L) {
                balance += amount;
            }
        }

        @Override
        public Component format(long amount) {
            return Component.literal(amount + " coins");
        }

        @Override
        public List<ItemStack> toStacks(long amount) {
            return List.of();
        }

        @Override
        public boolean hasItemForm() {
            return false;
        }
    }

    @Test
    void debitTakesWhatIsThereAndSaysHowMuch() {
        FakeCurrency currency = new FakeCurrency(4L);
        assertEquals(4L, currency.debit(null, 10L, TransactionReason.THEFT));
        assertEquals(0L, currency.balance(null));
    }

    @Test
    void debitIsNeverNegativeAndNeverInvents() {
        FakeCurrency currency = new FakeCurrency(10L);
        assertEquals(0L, currency.debit(null, 0L, TransactionReason.THEFT));
        assertEquals(0L, currency.debit(null, -5L, TransactionReason.THEFT));
        assertEquals(10L, currency.balance(null), "a non-positive request must move nothing");
    }

    @Test
    void tryChargeIsAllOrNothing() {
        FakeCurrency currency = new FakeCurrency(9L);
        assertFalse(currency.tryCharge(null, 10L, TransactionReason.FINE));
        assertEquals(9L, currency.balance(null), "a failed charge must leave the balance untouched");

        assertTrue(currency.tryCharge(null, 9L, TransactionReason.FINE));
        assertEquals(0L, currency.balance(null));
    }

    @Test
    void chargingNothingAlwaysSucceeds() {
        FakeCurrency currency = new FakeCurrency(0L);
        assertTrue(currency.tryCharge(null, 0L));
    }

    @Test
    void anAbstractCurrencyHasNoItemForm() {
        FakeCurrency currency = new FakeCurrency(50L);
        assertFalse(currency.hasItemForm());
        assertTrue(currency.toStacks(50L).isEmpty());
    }

    @Test
    void unknownIdsResolveToNothing() {
        assertTrue(Currencies.byId(new ResourceLocation("nosuchmod", "gold")).isEmpty());
        assertTrue(Currencies.byId(null).isEmpty());
    }

    @Test
    void theEmeraldDefaultIsAlwaysRegisteredAndActive() {
        // Registered at class init, so an economy mod that never loads cannot leave the server with
        // no currency at all and every fine silently free.
        assertTrue(Currencies.byId(new ResourceLocation("mcacrime", "emerald")).isPresent());
        assertEquals(new ResourceLocation("mcacrime", "emerald"), Currencies.active().id());
    }

    @Test
    void aRegisteredCurrencyIsFindableByItsOwnId() {
        Currencies.register(new FakeCurrency(0L));
        assertTrue(Currencies.byId(FakeCurrency.ID).isPresent());
    }

    // ------------------------------------------------------------------ the configurable item currency

    @Test
    void theItemCurrencyIsRegisteredAlongsideEmeralds() {
        assertTrue(Currencies.byId(ItemCurrency.ID).isPresent(),
                "'mcacrime:item' must exist without an economy mod, or the config option names nothing");
    }

    @Test
    void selectingTheItemCurrencyWorksWithNoRegistriesAtAll() {
        // With no item registry the configured item cannot be pinned, so the active currency is the
        // emerald fallback rather than a view that charges in an item nobody could look up. Selecting
        // the provider is still legal: the point is that it never throws and never leaves money offline.
        try {
            Currencies.reload("mcacrime:item", "minecraft:emerald");
            assertEquals(new ResourceLocation("mcacrime", "emerald"), Currencies.active().id());
        } finally {
            Currencies.reload("mcacrime:emerald", "minecraft:emerald");
        }
    }

    @Test
    void anUnresolvableItemStillLeavesAWorkingCurrency() {
        // No item registry exists in a unit test, so the lookup cannot succeed. That must degrade to
        // emeralds rather than throw: config load runs long before the registries are built, and an
        // exception there takes the whole mod down over a typo in one string.
        try {
            Currencies.reload("mcacrime:item", "nosuchmod:doubloon");
            assertEquals(new ResourceLocation("mcacrime", "emerald"), Currencies.active().id());
            assertTrue(ItemCurrency.INSTANCE.itemId() != null, "an unresolved item still names something");
        } finally {
            Currencies.reload("mcacrime:emerald", "minecraft:emerald");
        }
    }

    @Test
    void anUnknownProviderStillFallsBackToEmeralds() {
        try {
            Currencies.reload("nosuchmod:gold", "minecraft:emerald");
            assertEquals(new ResourceLocation("mcacrime", "emerald"), Currencies.active().id());
        } finally {
            Currencies.reload("mcacrime:emerald", "minecraft:emerald");
        }
    }

    // ------------------------------------------------------------------ per-item provider ids

    @Test
    void aBoundIdNamesExactlyOneItem() {
        assertEquals(new ResourceLocation("mcacrime", "item/minecraft/gold_nugget"),
                ItemCurrency.boundId(new ResourceLocation("minecraft", "gold_nugget")));
    }

    @Test
    void aBoundIdSurvivesBeingWrittenToDiskAndReadBack() {
        // This is the whole point: a provider id is stored as a string on a receipt, and must parse
        // back into the same id so the equality checks in BountyPayments/CrimeReconciler still match.
        ResourceLocation bound = ItemCurrency.boundId(new ResourceLocation("minecraft", "gold_nugget"));
        assertEquals(bound, ResourceLocation.tryParse(bound.toString()));
        assertEquals(new ResourceLocation("minecraft", "gold_nugget"), ItemCurrency.itemIdOf(bound).orElse(null));
    }

    @Test
    void onlyABoundIdParsesAsOne() {
        assertTrue(ItemCurrency.itemIdOf(ItemCurrency.ID).isEmpty());
        assertTrue(ItemCurrency.itemIdOf(new ResourceLocation("mcacrime", "emerald")).isEmpty());
        assertTrue(ItemCurrency.itemIdOf(new ResourceLocation("othermod", "item/minecraft/emerald")).isEmpty());
        assertTrue(ItemCurrency.itemIdOf(new ResourceLocation("mcacrime", "item/emerald")).isEmpty());
    }

    @Test
    void aBoundIdWhoseItemIsGoneResolvesToNothing() {
        // No registry here, so nothing resolves -- which is the same answer a removed mod gives, and
        // the answer that keeps a queued payment pending instead of paying it in today's currency.
        assertTrue(Currencies.byId(ItemCurrency.boundId(new ResourceLocation("minecraft", "emerald"))).isEmpty(),
                "an item that cannot be looked up must defer the payment, not substitute another item");
        assertTrue(Currencies.byId(ItemCurrency.boundId(new ResourceLocation("nosuchmod", "doubloon"))).isEmpty());
    }

    @Test
    void theConfigProviderIdStillResolvesToTheConfiguredInstance() {
        assertEquals(ItemCurrency.INSTANCE, Currencies.byId(ItemCurrency.ID).orElse(null));
    }

    @Test
    void creditBoundedDefaultsToAllOrUnknown() {
        // An abstract balance cannot overflow an inventory, so the default must report 0 (all arrived)
        // rather than inventing a remainder a bounty receipt would then re-queue forever.
        FakeCurrency currency = new FakeCurrency(0L);
        assertEquals(0L, currency.creditBounded(null, 25L, TransactionReason.BOUNTY));
        assertEquals(25L, currency.balance(null));
    }

    @Test
    void anAbstractCurrencyHasNoItemFormToRecordOnAReceipt() {
        assertTrue(new FakeCurrency(0L).itemForm().isEmpty());
    }
}
