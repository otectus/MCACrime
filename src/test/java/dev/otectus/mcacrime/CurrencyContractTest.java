package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.Currency;
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

        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mcacrime", "test_coin");

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
        assertTrue(Currencies.byId(ResourceLocation.fromNamespaceAndPath("nosuchmod", "gold")).isEmpty());
        assertTrue(Currencies.byId(null).isEmpty());
    }

    @Test
    void theEmeraldDefaultIsAlwaysRegisteredAndActive() {
        // Registered at class init, so an economy mod that never loads cannot leave the server with
        // no currency at all and every fine silently free.
        assertTrue(Currencies.byId(ResourceLocation.fromNamespaceAndPath("mcacrime", "emerald")).isPresent());
        assertEquals(ResourceLocation.fromNamespaceAndPath("mcacrime", "emerald"), Currencies.active().id());
    }

    @Test
    void aRegisteredCurrencyIsFindableByItsOwnId() {
        Currencies.register(new FakeCurrency(0L));
        assertTrue(Currencies.byId(FakeCurrency.ID).isPresent());
    }
}
