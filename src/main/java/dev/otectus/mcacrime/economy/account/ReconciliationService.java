package dev.otectus.mcacrime.economy.account;

import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.PropertyEscrow;
import dev.otectus.mcacrime.state.world.PropertyLot;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Operator decisions change bookkeeping only. No command here directly issues money or items. */
public final class ReconciliationService {
    private ReconciliationService() {}
    public enum Decision { DELIVERED, CANCELLED, RETRY }
    public enum Result { APPLIED, READ_ONLY, MISSING, STALE, TERMINAL, UNSUPPORTED, INVALID_NOTE, AUDIT_FULL }

    public static PropertyLot linkedProperty(CrimeWorldData data, UUID receiptId) {
        for (var lot : data.propertyEscrow()) if (PropertyEscrow.deliveryId(lot).equals(receiptId)) return lot;
        return null;
    }

    /** Inspect returns this token; a changed receipt or remainder requires another inspection. */
    public static UUID revision(CrimeWorldData data, TransactionReceipt receipt) {
        var lot = linkedProperty(data, receipt.id());
        return UUID.nameUUIDFromBytes((receipt.save() + "/" + (lot == null ? "" : lot.save()))
                .getBytes(StandardCharsets.UTF_8));
    }

    public static Result resolve(CrimeWorldData data, UUID id, UUID expectedRevision, Decision decision,
            String operator, String note, long now) {
        if (!ServerMutationGate.allows(data)) return Result.READ_ONLY;
        var receipt = data.transaction(id);
        if (receipt == null) return Result.MISSING;
        if (!revision(data, receipt).equals(expectedRevision)) return Result.STALE;
        if (receipt.state().terminal()) return Result.TERMINAL;
        if (decision == null) return Result.UNSUPPORTED;
        if (decision == Decision.RETRY && receipt.state() == TransactionReceipt.State.AWAITING_DELIVERY) return Result.UNSUPPORTED;
        if (note == null || note.isBlank() || note.length() > 256 || operator == null || operator.isBlank()
                || operator.length() > 128 || note.chars().anyMatch(Character::isISOControl)) return Result.INVALID_NOTE;
        var lot = linkedProperty(data, id);
        boolean bounty = receipt.reason() == TransactionReason.BOUNTY
                && data.bountyClaims().values().stream().anyMatch(claim ->
                    dev.otectus.mcacrime.bounty.BountyPayments.id(dev.otectus.mcacrime.bounty.BountyPayments.key(claim)).equals(id)
                    && claim.claimant().equals(receipt.to()) && claim.target().equals(receipt.from()));
        // Arbitrary source debits cannot be replayed safely. A property cancellation must not erase an owed lot.
        if (decision == Decision.RETRY && lot == null && !bounty
                || decision == Decision.CANCELLED && lot != null) return Result.UNSUPPORTED;
        var audit = new ReconciliationDecision(UUID.randomUUID(), id, operator, decision.name(), note.strip(),
                receipt.save(), lot == null ? null : lot.save(), now);
        if (!data.appendReconciliation(audit)) return Result.AUDIT_FULL;
        var next = decision == Decision.DELIVERED ? TransactionReceipt.State.DELIVERED
                : decision == Decision.RETRY && bounty ? TransactionReceipt.State.AWAITING_DELIVERY
                : TransactionReceipt.State.REJECTED;
        // No callbacks: audit, receipt and property disposition serialize as one world snapshot.
        if (decision == Decision.DELIVERED && lot != null) data.removePropertyLot(lot.lotId());
        data.putTransaction(receipt.withState(next, now));
        return Result.APPLIED;
    }
}
