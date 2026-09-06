package dev.otectus.mcacrime.economy.account;

import dev.otectus.mcacrime.economy.TransactionReason;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * What happened to one transfer of money, persisted (0.6.0, spec §4.5).
 *
 * <p>Before this, a transfer left behind a bare transaction id in a set. That set could say "this id
 * was used" and nothing else — not how much, not to whom, and critically not <em>how far the transfer
 * got</em>. A credit that threw after the debit had already been taken was indistinguishable from a
 * transfer that never started, so the only two available behaviours were to retry it (and pay twice)
 * or to drop it (and delete the money). A receipt makes that state nameable, which is what turns an
 * ambiguous failure into an operator's repair queue.
 *
 * <h2>The crash window, stated honestly</h2>
 *
 * <p>This is not exactly-once and does not claim to be. The debit side and the credit side may live in
 * different files — world {@code SavedData} on one side, a player's inventory or a third-party economy
 * on the other — and nothing available in Forge commits both atomically. What the ordering here buys
 * is a bounded, <em>named</em> failure: {@link State#SOURCE_DEBITED} is written and the store is
 * dirtied before anything is credited, so a crash in the window between them leaves a receipt saying
 * money left and never arrived. That receipt is never automatically retried — a non-idempotent credit
 * replayed is how duplication happens — it is left as {@link State#NEEDS_RECONCILIATION} for an
 * operator. A crash <em>before</em> the store is flushed loses the receipt along with the debit, which
 * is the surviving hole and is the reason high-value transfers should ride escrow instead.
 */
public record TransactionReceipt(UUID id, State state, String providerId, long amount,
                                 TransactionReason reason, @Nullable UUID from, @Nullable UUID to,
                                 long payloadHash, long stamp) {

    /** The lifecycle of one transfer (spec §4.5). Persisted by name; ordinals are never written. */
    public enum State {
        /** Priced and validated; nothing has moved. */
        PREPARED,
        /** The source has been debited and the credit has not been attempted or has not returned. */
        SOURCE_DEBITED,
        /** The debit is done and the credit is on its way through something asynchronous. */
        DELIVERY_PENDING,
        /** Both sides completed. Terminal. */
        DELIVERED,
        /** Nothing moved; a precondition refused it. Terminal. */
        REJECTED,
        /**
         * The source was debited and the credit did not complete. Deliberately <b>not</b> terminal and
         * never pruned: it is a debt this mod owes somebody and an operator has to settle it.
         */
        NEEDS_RECONCILIATION;

        /** Whether this receipt has finished moving and may eventually be forgotten. */
        public boolean terminal() {
            return this == DELIVERED || this == REJECTED;
        }
    }

    public TransactionReceipt {
        reason = reason == null ? TransactionReason.OTHER : reason;
        state = state == null ? State.PREPARED : state;
        providerId = providerId == null ? "" : providerId;
    }

    /** The same receipt moved to another state at {@code now}. */
    public TransactionReceipt withState(State next, long now) {
        return new TransactionReceipt(id, next, providerId, amount, reason, from, to, payloadHash, now);
    }

    /** True once the transfer completed. The only state a caller may treat as "the money arrived". */
    public boolean delivered() {
        return state == State.DELIVERED;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        // Names, never ordinals: a state inserted into the enum must not silently re-label saved rows.
        tag.putString("state", state.name());
        tag.putString("provider", providerId);
        tag.putLong("amount", amount);
        tag.putString("reason", reason.name());
        if (from != null) {
            tag.putUUID("from", from);
        }
        if (to != null) {
            tag.putUUID("to", to);
        }
        tag.putLong("hash", payloadHash);
        tag.putLong("stamp", stamp);
        return tag;
    }

    /** Throws when the row carries no id; the caller quarantines that one entry. */
    public static TransactionReceipt load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("id")) {
            throw new IllegalArgumentException("transaction receipt has no id");
        }
        return new TransactionReceipt(tag.getUUID("id"), parseState(tag.getString("state")),
                tag.getString("provider"), tag.getLong("amount"), parseReason(tag.getString("reason")),
                tag.hasUUID("from") ? tag.getUUID("from") : null,
                tag.hasUUID("to") ? tag.getUUID("to") : null,
                tag.getLong("hash"), tag.getLong("stamp"));
    }

    /**
     * An unrecognised state reads as {@link State#NEEDS_RECONCILIATION} rather than as a default.
     *
     * <p>A name this build does not know was written by one that did, about money that did move. The
     * safe reading of "I cannot tell what happened to this transfer" is the state that means exactly
     * that and is never pruned or retried.
     */
    private static State parseState(String name) {
        for (State state : State.values()) {
            if (state.name().equals(name)) {
                return state;
            }
        }
        return State.NEEDS_RECONCILIATION;
    }

    private static TransactionReason parseReason(String name) {
        for (TransactionReason reason : TransactionReason.values()) {
            if (reason.name().equals(name)) {
                return reason;
            }
        }
        return TransactionReason.OTHER;
    }
}
