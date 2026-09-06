package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.economy.account.EconomicTransactionService;
import dev.otectus.mcacrime.economy.account.PurseAccess;
import dev.otectus.mcacrime.economy.account.TransactionReceipt;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Money moving, and the two ways it must not (spec §4.5).
 *
 * <p>Both properties here are about the boundary between "the player paid" and "the world recorded
 * it", which no file system will make atomic for us. The first says a refusal must land entirely on
 * one side of that boundary: if the ledger will not accept the settlement, nothing is taken and no
 * receipt is written, so the player can simply try again. The second says an <em>ambiguous</em>
 * transfer must land visibly in the middle: when a credit throws after the debit is done, the money is
 * neither quietly recreated nor quietly deleted, it is recorded as owed and never retried.
 *
 * <p>All of it runs against {@link CrimeWorldData} and a {@link PurseAccess} lambda, because the only
 * way to ask a currency to throw on demand is to write the currency.
 */
class EconomicTransactionTest {

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final UUID TRANSACTION = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final ResourceLocation THEFT = new ResourceLocation("mcacrime", "theft");
    /** Fine base 10, 2 per Heat, jailable at 100, no Blue discount, outlaws may pay, 8 cases a payment. */
    private static final SettlementPolicy.Settings SETTINGS =
            new SettlementPolicy.Settings(true, 10, 2, 100, 1.0D, true, 8);

    // ------------------------------------------------------------------ T03

    @Test
    void aVetoedSettlementTakesNothingAndWritesNoReceipt() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord open = new CrimeRecord(UUID.randomUUID(), OFFENDER, null, THEFT, OptionalInt.empty(),
                null, true, Set.of(), 50L, 10L, -5L, 20L, 0L, Resolution.UNRESOLVED, 0L, List.of(), null,
                Map.of());
        data.addRecord(open);

        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 30L, Band.GREY, List.of(), true,
                0L, SETTINGS);
        assertTrue(quote.ok(), "the fixture should price, or this test proves nothing");

        AtomicLong debited = new AtomicLong();
        // The gate refuses every case, which is what a companion mod cancelling the resolution looks
        // like. It has to be consulted before the purse, not after.
        FineService.Payment payment = FineService.pay(data, 10L, quote,
                amount -> {
                    debited.addAndGet(amount);
                    return true;
                },
                (record, resolution) -> false, TRANSACTION);

        assertFalse(payment.paid(), "a vetoed settlement reported itself as paid");
        assertEquals(0L, debited.get(), "the purse was charged for a settlement that never happened");
        assertNull(data.transaction(TRANSACTION), "a refusal before the debit left a receipt behind");
        assertTrue(data.transactions().isEmpty());
        assertEquals(Resolution.UNRESOLVED, data.recordById(open.id()).orElseThrow().resolution());
    }

    // ------------------------------------------------------------------ T12

    @Test
    void aCreditThatThrowsLeavesTheDebitRecordedAndUnrepeated() {
        CrimeWorldData data = new CrimeWorldData();
        AtomicLong debited = new AtomicLong();
        AtomicLong creditAttempts = new AtomicLong();
        PurseAccess exploding = new PurseAccess() {
            @Override
            public long available() {
                return 1_000L;
            }

            @Override
            public long debit(long amount, TransactionReason reason) {
                debited.addAndGet(amount);
                return amount;
            }

            @Override
            public boolean credit(long amount, TransactionReason reason) {
                creditAttempts.incrementAndGet();
                throw new IllegalStateException("the bank fell over");
            }
        };

        TransactionReceipt first = EconomicTransactionService.transfer(data, TRANSACTION,
                TransactionReason.RANSOM, "mcacrime:test", exploding, 40L, false, null, null, 100L);

        assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, first.state());
        assertFalse(first.delivered());
        assertEquals(40L, debited.get(), "the debit is what makes this a debt rather than a non-event");
        assertEquals(40L, first.amount(), "the receipt must say how much is owed");

        TransactionReceipt persisted = data.transaction(TRANSACTION);
        assertNotNull(persisted, "an undelivered transfer has to survive the session that lost it");
        assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, persisted.state());

        // The same id offered again: an ambiguous credit replayed is how money is minted.
        TransactionReceipt replay = EconomicTransactionService.transfer(data, TRANSACTION,
                TransactionReason.RANSOM, "mcacrime:test", exploding, 40L, false, null, null, 200L);
        assertEquals(TransactionReceipt.State.NEEDS_RECONCILIATION, replay.state());
        assertEquals(1L, creditAttempts.get(), "the failed credit was retried");
        assertEquals(40L, debited.get(), "the replay debited the source a second time");
    }

    @Test
    void aDeliveredTransferClosesAsDelivered() {
        CrimeWorldData data = new CrimeWorldData();
        TransactionReceipt receipt = EconomicTransactionService.transfer(data, TRANSACTION,
                TransactionReason.BOUNTY, "mcacrime:test", working(500L), 120L, false, null, null, 7L);

        assertTrue(receipt.delivered());
        assertEquals(120L, receipt.amount());
        assertEquals(TransactionReceipt.State.DELIVERED, data.transaction(TRANSACTION).state());
    }

    @Test
    void anUnaffordableExactTransferMovesNothingAndStaysRetryable() {
        CrimeWorldData data = new CrimeWorldData();
        TransactionReceipt receipt = EconomicTransactionService.transfer(data, TRANSACTION,
                TransactionReason.BAIL, "mcacrime:test", working(10L), 120L, false, null, null, 7L);

        assertEquals(TransactionReceipt.State.REJECTED, receipt.state());
        // Nothing happened, so nothing is remembered: the id must still be usable when the player has
        // the money. A rejection that burned the id would make the second attempt fail too.
        assertNull(data.transaction(TRANSACTION));
    }

    @Test
    void aReceiptRoundTripsThroughNbtByName() {
        TransactionReceipt receipt = new TransactionReceipt(TRANSACTION,
                TransactionReceipt.State.NEEDS_RECONCILIATION, "mcacrime:emerald", 64L,
                TransactionReason.FINE, OFFENDER, null, 12345L, 900L);
        CompoundTag tag = receipt.save();

        assertEquals("NEEDS_RECONCILIATION", tag.getString("state"), "a state stored as an ordinal would "
                + "be re-labelled by anybody who inserted a value into the enum");
        assertEquals(receipt, TransactionReceipt.load(tag));
    }

    @Test
    void terminalReceiptsAgeOutAndReconciliationDebtsDoNot() {
        CrimeWorldData data = new CrimeWorldData();
        long day = 24000L;
        data.putTransaction(new TransactionReceipt(UUID.randomUUID(), TransactionReceipt.State.DELIVERED,
                "mcacrime:test", 5L, TransactionReason.FINE, null, null, 0L, 0L));
        UUID owed = UUID.randomUUID();
        data.putTransaction(new TransactionReceipt(owed, TransactionReceipt.State.NEEDS_RECONCILIATION,
                "mcacrime:test", 5L, TransactionReason.FINE, null, null, 0L, 0L));

        assertEquals(1, data.pruneTransactions(40L * day));
        assertNotNull(data.transaction(owed), "a debt this mod owes somebody was pruned away");
    }

    /** A purse with {@code balance} in it that always accepts the credit. */
    private static PurseAccess working(long balance) {
        return new PurseAccess() {
            @Override
            public long available() {
                return balance;
            }

            @Override
            public long debit(long amount, TransactionReason reason) {
                return Math.min(balance, amount);
            }

            @Override
            public boolean credit(long amount, TransactionReason reason) {
                return true;
            }
        };
    }
}
