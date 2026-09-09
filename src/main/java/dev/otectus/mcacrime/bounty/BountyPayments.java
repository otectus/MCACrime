package dev.otectus.mcacrime.bounty;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.economy.account.TransactionReceipt;
import dev.otectus.mcacrime.state.world.BountyClaimRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** A claim reserves its payment in the same SavedData before any external credit is attempted. */
public final class BountyPayments {
    private BountyPayments() {}

    @FunctionalInterface public interface Credit {
        /** Exact amount still owed, or -1 for an ambiguous result. No automatic retry of ambiguity. */
        long deliver(long amount);
    }

    public static UUID id(BountyClaimKey key) {
        return UUID.nameUUIDFromBytes(("bounty_payment:" + key.asKey()).getBytes(StandardCharsets.UTF_8));
    }

    public static BountyClaimKey key(BountyClaimRecord claim) {
        return new BountyClaimKey(claim.target(), claim.warrantId(), claim.revision());
    }

    public static boolean reserve(CrimeWorldData data, BountyClaimKey key, UUID claimant, long amount,
            long now, BountyResolutionType type, String provider) {
        if (!ServerMutationGate.allows(data) || key == null || key.target() == null || key.warrantId() == null
                || claimant == null || claimant.equals(key.target()) || amount < 0 || provider == null) return false;
        UUID id = id(key);
        if (data.bountyClaim(key.asKey()) != null || data.transaction(id) != null || data.hasTransactionReceipt(id)) return false;
        var reservation = new TransactionReceipt(id, TransactionReceipt.State.PREPARED, provider, amount,
                TransactionReason.BOUNTY, key.target(), claimant, key.asKey().hashCode(), now);
        if (!data.putTransaction(reservation).stored()) return false;
        if (!BountyClaimLedger.tryClaim(data, key, claimant, amount, now, type)) {
            data.cancelPreparedTransaction(reservation);
            return false;
        }
        // No callbacks between the claim and its queued payment. Both serialize in one world snapshot.
        data.putTransaction(reservation.withState(TransactionReceipt.State.AWAITING_DELIVERY, now));
        return true;
    }

    /** True only for a newly completed payment, so events/rewards are never replayed by collection. */
    public static boolean deliver(CrimeWorldData data, BountyClaimKey key, UUID claimant, String provider,
            Credit credit, long now) {
        if (!ServerMutationGate.allows(data) || key == null || claimant == null || credit == null) return false;
        BountyClaimRecord claim = data.bountyClaim(key.asKey());
        TransactionReceipt queued = data.transaction(id(key));
        if (claim == null || !claimant.equals(claim.claimant()) || queued == null
                || queued.state() != TransactionReceipt.State.AWAITING_DELIVERY
                || queued.reason() != TransactionReason.BOUNTY || !claimant.equals(queued.to())
                || !key.target().equals(queued.from()) || queued.amount() < 0
                || !Objects.equals(queued.providerId(), provider)) return false;
        var attempt = queued.withState(TransactionReceipt.State.DELIVERY_PENDING, now);
        if (!data.putTransaction(attempt).stored()) return false;
        long left;
        try { left = queued.amount() == 0 ? 0 : credit.deliver(queued.amount()); }
        catch (RuntimeException failure) {
            McaCrime.LOGGER.error("Bounty payment {} has an uncertain outcome; automatic retry suspended", attempt.id(), failure);
            left = -1;
        }
        if (!attempt.equals(data.transaction(attempt.id()))) return false;
        if (left < 0 || left > queued.amount()) {
            data.putTransaction(attempt.withState(TransactionReceipt.State.NEEDS_RECONCILIATION, now));
            return false;
        }
        if (left == 0) {
            data.putTransaction(attempt.withState(TransactionReceipt.State.DELIVERED, now));
            data.recordTransactionReceipt(attempt.id());
            return true;
        }
        data.putTransaction(new TransactionReceipt(queued.id(), TransactionReceipt.State.AWAITING_DELIVERY,
                queued.providerId(), left, queued.reason(), queued.from(), queued.to(), queued.payloadHash(), now));
        return false;
    }
}
