package dev.otectus.mcacrime.economy.account;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Sole writer for finite purse/treasury transfers, and the only place a {@link TransactionReceipt} is
 * minted.
 *
 * <p>The order below is the whole class, and it is the reverse of what the code used to do. A
 * transfer used to claim its id, debit, and then credit blind: the claim said "this will happen", the
 * credit was unchecked, and a credit that failed left an id marked as spent with the money nowhere.
 * Now the debit runs first, {@link TransactionReceipt.State#SOURCE_DEBITED} is written and the store
 * dirtied <em>before</em> anything is credited, and the outcome of the credit is what closes the
 * receipt. A credit that fails or throws leaves {@link TransactionReceipt.State#NEEDS_RECONCILIATION}
 * and is never retried automatically — replaying a non-idempotent credit is how money is minted.
 *
 * <p>This is not exactly-once and the receipt's javadoc says where the remaining window is.
 */
public final class EconomicTransactionService {

    private EconomicTransactionService() {
    }

    /**
     * The core transfer, over a {@link PurseAccess} and nothing else.
     *
     * @param requested    the amount to move
     * @param allowPartial true when moving less than asked is a success (a mugging takes what is
     *                     there); false when the transfer is all-or-nothing (a fine, a ransom)
     * @return the receipt. Never null; {@link TransactionReceipt#delivered()} is the only success test
     */
    public static synchronized TransactionReceipt transfer(CrimeWorldData world, UUID transactionId,
                                                           TransactionReason reason, String providerId,
                                                           PurseAccess access, long requested,
                                                           boolean allowPartial, @Nullable UUID from,
                                                           @Nullable UUID to, long now) {
        TransactionReceipt prepared = new TransactionReceipt(transactionId,
                TransactionReceipt.State.PREPARED, providerId, Math.max(0L, requested), reason, from, to,
                payloadHash(transactionId, requested, from, to), now);
        if (world == null || transactionId == null || access == null || requested <= 0L) {
            return prepared.withState(TransactionReceipt.State.REJECTED, now);
        }

        // A transfer that has already been attempted is answered from its receipt, whatever that
        // receipt says. That includes NEEDS_RECONCILIATION: an ambiguous credit is precisely the one
        // that must not be run a second time.
        TransactionReceipt existing = world.transaction(transactionId);
        if (existing != null) {
            return existing;
        }
        if (world.hasTransactionReceipt(transactionId)) {
            // A pre-0.6.0 id, recorded when only "used" could be expressed. Nothing more is knowable.
            return prepared.withState(TransactionReceipt.State.REJECTED, now);
        }
        if (!allowPartial && access.available() < requested) {
            return prepared.withState(TransactionReceipt.State.REJECTED, now);
        }

        long debited = access.debit(requested, reason);
        if (debited <= 0L) {
            // Nothing moved, so nothing is persisted: the id stays free and the caller may re-quote.
            return prepared.withState(TransactionReceipt.State.REJECTED, now);
        }

        TransactionReceipt debitedReceipt = new TransactionReceipt(transactionId,
                TransactionReceipt.State.SOURCE_DEBITED, providerId, debited, reason, from, to,
                prepared.payloadHash(), now);
        world.putTransaction(debitedReceipt);
        world.recordTransactionReceipt(transactionId);
        world.setDirty(); // the debit is durable before the credit is attempted, not after

        boolean credited;
        try {
            credited = access.credit(debited, reason);
        } catch (RuntimeException e) {
            McaCrime.LOGGER.error("MCA: Crime debited {} for transaction {} and the credit threw. The "
                    + "receipt is held for reconciliation and will not be retried.", debited, transactionId, e);
            credited = false;
        }
        TransactionReceipt closed = debitedReceipt.withState(credited
                ? TransactionReceipt.State.DELIVERED
                : TransactionReceipt.State.NEEDS_RECONCILIATION, now);
        world.putTransaction(closed);
        if (!credited) {
            McaCrime.LOGGER.warn("MCA: Crime holds an undelivered transfer of {} (transaction {}); it is "
                    + "recorded as needing reconciliation and no retry will be attempted.", debited, transactionId);
        }
        return closed;
    }

    /**
     * A villager's purse paying a player.
     *
     * <p>Partial by design: a mugging that finds four emeralds where it wanted ten takes four.
     *
     * @return how much actually reached the player, which is zero on any failure
     */
    public static int transferPurseToPlayer(CrimeWorldData world, UUID transactionId,
                                            TransactionReason reason, VillagerPurse purse,
                                            ServerPlayer player, int requested, long now) {
        if (purse == null || player == null) {
            return 0;
        }
        TransactionReceipt receipt = transfer(world, transactionId, reason, providerId(),
                purseAccess(purse, player), requested, true, null, player.getUUID(), now);
        return receipt.delivered() ? (int) Math.min(Integer.MAX_VALUE, receipt.amount()) : 0;
    }

    /** A village treasury paying a player. All-or-nothing: a partial ransom refund is not a refund. */
    public static boolean transferTreasuryToPlayer(CrimeWorldData world, UUID transactionId,
                                                   TransactionReason reason, String treasury,
                                                   long initialBalance, ServerPlayer player, long amount,
                                                   long now) {
        if (player == null || world == null) {
            return false;
        }
        return transfer(world, transactionId, reason, providerId(),
                treasuryAccess(world, treasury, initialBalance, player), amount, false, null,
                player.getUUID(), now).delivered();
    }

    /** One player paying another. All-or-nothing. */
    public static boolean transferPlayerToPlayer(CrimeWorldData world, UUID transactionId,
                                                 TransactionReason reason, ServerPlayer payer,
                                                 ServerPlayer recipient, long amount, long now) {
        if (payer == null || recipient == null) {
            return false;
        }
        return transfer(world, transactionId, reason, providerId(), playerAccess(payer, recipient), amount,
                false, payer.getUUID(), recipient.getUUID(), now).delivered();
    }

    // ------------------------------------------------------------------ bindings

    private static String providerId() {
        return Currencies.active().id().toString();
    }

    private static PurseAccess purseAccess(VillagerPurse purse, ServerPlayer player) {
        return new PurseAccess() {
            @Override
            public long available() {
                return Math.max(0L, purse.balance());
            }

            @Override
            public long debit(long amount, TransactionReason reason) {
                return purse.withdraw((int) Math.min(Integer.MAX_VALUE, Math.max(0L, amount)));
            }

            @Override
            public boolean credit(long amount, TransactionReason reason) {
                return Currencies.active().tryCredit(player, amount, reason);
            }
        };
    }

    private static PurseAccess treasuryAccess(CrimeWorldData world, String treasury, long initialBalance,
                                              ServerPlayer player) {
        return new PurseAccess() {
            @Override
            public long available() {
                return world.treasuryBalance(treasury, initialBalance);
            }

            @Override
            public long debit(long amount, TransactionReason reason) {
                return world.withdrawTreasury(treasury, amount, initialBalance) ? amount : 0L;
            }

            @Override
            public boolean credit(long amount, TransactionReason reason) {
                return Currencies.active().tryCredit(player, amount, reason);
            }
        };
    }

    private static PurseAccess playerAccess(ServerPlayer payer, ServerPlayer recipient) {
        return new PurseAccess() {
            @Override
            public long available() {
                return Currencies.active().balance(payer);
            }

            @Override
            public long debit(long amount, TransactionReason reason) {
                return Currencies.active().tryCharge(payer, amount, reason) ? amount : 0L;
            }

            @Override
            public boolean credit(long amount, TransactionReason reason) {
                return Currencies.active().tryCredit(recipient, amount, reason);
            }
        };
    }

    /**
     * A cheap identity for what this transfer was supposed to move, stored on the receipt.
     *
     * <p>Not a cryptographic digest and not trying to be: its only job is to let an operator reading a
     * reconciliation queue tell two receipts that look alike apart, and to catch a receipt whose
     * amount or parties were edited underneath it.
     */
    private static long payloadHash(@Nullable UUID transactionId, long amount, @Nullable UUID from,
                                    @Nullable UUID to) {
        long hash = amount * 31L;
        hash = hash * 31L + (transactionId == null ? 0L : transactionId.hashCode());
        hash = hash * 31L + (from == null ? 0L : from.hashCode());
        hash = hash * 31L + (to == null ? 0L : to.hashCode());
        return hash;
    }
}
