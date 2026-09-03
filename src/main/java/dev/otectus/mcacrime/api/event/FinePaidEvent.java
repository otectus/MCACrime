package dev.otectus.mcacrime.api.event;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Fired once when a player pays a fine, after the payment has been taken and the affected cases
 * marked settled.
 *
 * <p>One event per payment, not one per case. A player clearing four offences with a single handful
 * of emeralds performed one act, and a listener that wanted to congratulate them, advance a quest, or
 * write a log line should fire once.
 *
 * <p>{@code transactionId} is unique per payment, so a listener can recognise a redelivered event
 * rather than crediting the same payment twice.
 */
public final class FinePaidEvent extends CrimeEvent {

    private final UUID transactionId;
    private final List<UUID> affectedCaseIds;
    private final long amount;
    private final long oldHeat;
    private final long newHeat;

    public FinePaidEvent(ServerPlayer payer, UUID transactionId, List<UUID> affectedCaseIds,
                         long amount, long oldHeat, long newHeat) {
        super(payer);
        this.transactionId = transactionId;
        this.affectedCaseIds = affectedCaseIds == null ? List.of() : List.copyOf(affectedCaseIds);
        this.amount = amount;
        this.oldHeat = oldHeat;
        this.newHeat = newHeat;
    }

    /** Unique per payment. */
    public UUID getTransactionId() {
        return transactionId;
    }

    /**
     * Exactly which cases this payment settled, as an immutable list.
     *
     * <p>May be empty: a player can pay off Heat that no open case accounts for, which happens when
     * the offences behind it went unwitnessed or have already aged out.
     */
    public List<UUID> getAffectedCaseIds() {
        return affectedCaseIds;
    }

    /** What was actually taken, in currency units. */
    public long getAmount() {
        return amount;
    }

    public long getOldHeat() {
        return oldHeat;
    }

    public long getNewHeat() {
        return newHeat;
    }
}
