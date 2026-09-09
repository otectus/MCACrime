package dev.otectus.mcacrime.state.world;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.economy.TransactionReason;
import dev.otectus.mcacrime.economy.account.TransactionReceipt;
import net.minecraft.nbt.CompoundTag;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Delivers saved property with bounded reentry protection and explicit ambiguous outcomes. */
public final class PropertyEscrow {
    private PropertyEscrow() {}

    @FunctionalInterface
    public interface Handover {
        /** Return the exact remainder; the original lot means no transfer. Null is an unknown outcome. */
        PropertyLot deliver(PropertyLot lot);
    }

    /** A distinct persisted attempt for each remaining payload, stable across save/reload. */
    public static UUID deliveryId(PropertyLot lot) {
        return UUID.nameUUIDFromBytes(("property_delivery:" + lot.save()).getBytes(StandardCharsets.UTF_8));
    }

    public static int deliverPending(CrimeWorldData data, UUID owner, Handover handover) {
        return deliverPending(data, owner, handover, 0L);
    }

    public static int deliverPending(CrimeWorldData data, UUID owner, Handover handover, long now) {
        if (!ServerMutationGate.allows(data) || owner == null || handover == null) return 0;
        int closed = 0;
        for (PropertyLot lot : data.propertyEscrowFor(owner)) {
            if (!lot.equals(data.propertyLot(lot.lotId()))) continue;
            UUID id = deliveryId(lot);
            TransactionReceipt previous = data.transaction(id);
            // A pending/ambiguous external operation must never be attempted a second time.
            if (previous != null && previous.state() != TransactionReceipt.State.REJECTED
                    || previous == null && data.hasTransactionReceipt(id)) continue;
            var attempt = new TransactionReceipt(id, TransactionReceipt.State.DELIVERY_PENDING,
                    lot.providerId(), lot.currency(), TransactionReason.RECOVERY, null, owner,
                    lot.hashCode(), Math.max(now, lot.createdAt()));
            if (!data.putTransaction(attempt).stored()) continue;
            try {
                PropertyLot left = handover.deliver(lot);
                if (left == null || !lot.equals(data.propertyLot(lot.lotId())) || !validRemainder(lot, left)) {
                    data.putTransaction(attempt.withState(TransactionReceipt.State.NEEDS_RECONCILIATION, attempt.stamp()));
                    McaCrime.LOGGER.warn("Property delivery {} returned an unknown result; retaining its receipt for reconciliation", id);
                    continue;
                }
                if (left.equals(lot)) {
                    data.putTransaction(attempt.withState(TransactionReceipt.State.REJECTED, attempt.stamp()));
                    continue; // No room/provider unavailable; a later ordinary attempt is allowed.
                }
                if (left.empty()) {
                    data.removePropertyLot(lot.lotId()); closed++;
                } else data.putPropertyLot(left);
                data.putTransaction(attempt.withState(TransactionReceipt.State.DELIVERED, attempt.stamp()));
            } catch (RuntimeException exception) {
                data.putTransaction(attempt.withState(TransactionReceipt.State.NEEDS_RECONCILIATION, attempt.stamp()));
                McaCrime.LOGGER.error("Property delivery {} failed; automatic retry is suspended", id, exception);
            }
        }
        return closed;
    }

    private static boolean validRemainder(PropertyLot original, PropertyLot left) {
        if (original.equals(left)) return true;
        if (!original.remaining(left.stackTag(), left.currency()).equals(left)
                || left.currency() > original.currency()) return false;
        if (!left.hasStack()) return true;
        if (!original.hasStack()) return false;
        CompoundTag before = original.stackTag(), after = left.stackTag();
        int beforeCount = before.getByte("Count"), afterCount = after.getByte("Count");
        before.remove("Count"); after.remove("Count");
        return afterCount > 0 && afterCount <= beforeCount && Objects.equals(before, after);
    }
}
