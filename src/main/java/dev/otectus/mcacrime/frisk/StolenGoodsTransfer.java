package dev.otectus.mcacrime.frisk;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.engine.CrimeReconciler;
import dev.otectus.mcacrime.mug.npc.StolenGoodsLedger;
import dev.otectus.mcacrime.mug.npc.TheftExecutor;
import dev.otectus.mcacrime.network.ActionValidation;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.PropertyLot;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Taking something out of a thief's stolen-goods slots (0.7.7).
 *
 * <p>Who is searching decides what the take is, and the record decides whose property it is:
 *
 * <ul>
 *   <li><b>The owner</b> recovers it. No theft is filed -- nobody can steal their own purse back -- and
 *       the record leaves the ledger.</li>
 *   <li><b>A lawful search</b> (a custodian, or a guard acting for a jail or the village) seals it as
 *       evidence <em>for its owner</em>, not for the thief being searched: the generic lawful seizure
 *       escrows a stack back to the subject, which for stolen property would hand it to the thief.</li>
 *   <li><b>Anybody else</b> is stealing it again, under the same rules as any criminal search. The new
 *       theft is filed with the original owner still named as the owner: the thief it was taken from is
 *       never recorded as the owner of somebody else's property.</li>
 * </ul>
 *
 * <p>The owner's and the lawful cases go through the property escrow: the exact portion becomes a lot
 * owed to the owner and the record is reduced or removed in the same world-data write, then the escrow's
 * own receipt-backed delivery hands it over (to the owner's inventory or balance, or later, at login, if
 * they are offline, full or the currency provider is unavailable). So a failed or ambiguous delivery
 * leaves the property owed, never lost and never doubled. A stolen balance is never turned into items:
 * it moves only as currency through the provider that took it, and only the owner or a lawful search can
 * take a purse at all.
 *
 * <p>Every take is validated against the live session, reach, line of sight, restraint, custody and the
 * slot's revision before anything moves, and is identified by a transfer id derived from the session,
 * the slot, the revision and the count, so a replayed packet and a second searcher both find the record
 * already gone or changed and move nothing.
 */
public final class StolenGoodsTransfer {

    /** What one take of a stolen record is. */
    public enum Disposition { RECOVERY, EVIDENCE, THEFT }

    private StolenGoodsTransfer() {
    }

    /** Pure: who gets the property, from who is searching and what kind of search it is. */
    public static Disposition disposition(@Nullable UUID searcher, UUID owner, SeizureKind kind) {
        if (searcher != null && searcher.equals(owner)) {
            return Disposition.RECOVERY;
        }
        return kind == SeizureKind.LAWFUL_SEARCH ? Disposition.EVIDENCE : Disposition.THEFT;
    }

    /**
     * Condition 6 for a stolen slot: would the destination take exactly this, asked before anything moves.
     *
     * <p>A purse answers yes here so that the transfer itself can refuse with the precise reason (not
     * yours, or the currency is unavailable) rather than "no room".
     */
    public static boolean destinationWouldTake(FriskSession session, ServerPlayer searcher, LivingEntity subject,
                                               FriskSlotRef ref, int count) {
        StolenGoodsRecord record = StolenGoodsInventoryProvider.recordAt(subject, ref);
        if (record == null) {
            return false;
        }
        if (!record.hasStack()) {
            return true;
        }
        ItemStack stack = record.stack();
        if (stack.isEmpty() || count <= 0 || count > stack.getCount()) {
            return false;
        }
        return disposition(searcher.getUUID(), record.owner(), session.seizureKind()) == Disposition.EVIDENCE
                || InventoryCapacity.wouldTake(searcher.getInventory(), stack.copyWithCount(count));
    }

    /** One take, already past the session-wide validation in {@link FriskTransaction#commit}. */
    static FriskTransaction.Result commit(ServerPlayer searcher, LivingEntity subject, FriskSession session,
                                          FriskSlotRef ref, int viewIndex, int expectedRevision, int count,
                                          CrimeWorldData data, long now) {
        if (!ActionValidation.hasLineOfSight(searcher, subject)) {
            return FriskTransaction.Result.refused(FriskRefusal.NOT_VISIBLE);
        }
        StolenGoodsRecord record = StolenGoodsInventoryProvider.recordAt(subject, ref);
        if (record == null) {
            return FriskTransaction.Result.refused(FriskRefusal.SLOT_CHANGED);
        }
        UUID transferId = SeizureLedger.transferId(session.id(), viewIndex, expectedRevision, count);
        if (session.alreadyApplied(transferId)) {
            return FriskTransaction.Result.refused(FriskRefusal.ALREADY_DONE);
        }
        ItemStack shown = StolenGoodsInventoryProvider.display(subject, record);
        Disposition disposition = disposition(searcher.getUUID(), record.owner(), session.seizureKind());
        MinecraftServer server = searcher.getServer();
        String feedback;
        ItemStack moved;
        if (record.hasStack()) {
            ItemStack stack = record.stack();
            if (stack.isEmpty() || count <= 0 || count > stack.getCount()) {
                return FriskTransaction.Result.refused(FriskRefusal.SLOT_CHANGED);
            }
            ItemStack portion = stack.copyWithCount(count);
            ItemStack remainder = stack.copyWithCount(stack.getCount() - count);
            moved = shown.copyWithCount(count);
            if (disposition == Disposition.THEFT) {
                FriskRefusal refusal = steal(searcher, data, record, transferId, portion, remainder, now);
                if (refusal != FriskRefusal.NONE) {
                    return FriskTransaction.Result.refused(refusal);
                }
                feedback = session.seizureKind().messageKey();
            } else {
                if (disposition == Disposition.RECOVERY
                        && !InventoryCapacity.wouldTake(searcher.getInventory(), portion)) {
                    return FriskTransaction.Result.refused(FriskRefusal.DESTINATION_REFUSED);
                }
                PropertyLot lot = PropertyLot.ofStack(lotId(transferId), record.owner(), portion,
                        record.transactionId(), now);
                if (!moveToEscrow(data, record, lot, remainder)) {
                    return FriskTransaction.Result.refused(FriskRefusal.LEDGER_FULL);
                }
                feedback = deliver(server, record.owner(), lot, disposition);
            }
        } else {
            if (record.currency() <= 0L || count != 1) {
                return FriskTransaction.Result.refused(FriskRefusal.SLOT_CHANGED);
            }
            if (disposition == Disposition.THEFT) {
                return FriskTransaction.Result.refused(FriskRefusal.NOT_YOUR_PURSE);
            }
            // Whatever provider the purse was taken by, the lot keeps naming it: delivery pays it only
            // through that provider, and holds it in escrow until it can (a later login, a restored mod).
            PropertyLot lot = PropertyLot.ofCurrency(lotId(transferId), record.owner(), record.currency(),
                    record.providerId(), record.transactionId(), now);
            if (!moveToEscrow(data, record, lot, null)) {
                return FriskTransaction.Result.refused(FriskRefusal.LEDGER_FULL);
            }
            moved = shown;
            feedback = deliver(server, record.owner(), lot, disposition);
        }
        session.markApplied(transferId);
        session.stampTransfer(now);
        dev.otectus.mcacrime.util.CrimeDebug.crime("stolen goods {} ({}): {} took {} from thief {} for owner {}",
                record.transactionId(), disposition, searcher.getUUID(), moved, subject.getUUID(), record.owner());
        return new FriskTransaction.Result(FriskRefusal.NONE, transferId, moved, feedback);
    }

    /** The escrow lot id for one transfer: derived, so the same transfer can never mint two lots. */
    static UUID lotId(UUID transferId) {
        return UUID.nameUUIDFromBytes(("lot:frisk:" + transferId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes the owner's lot and takes the same portion off the thief's record, as one world-data change.
     * The lot is written first and taken back if the record cannot be reduced, so the property is in
     * exactly one of the two stores whatever happens.
     */
    private static boolean moveToEscrow(CrimeWorldData data, StolenGoodsRecord record, PropertyLot lot,
                                        @Nullable ItemStack remainder) {
        if (data.propertyLot(lot.lotId()) != null || !data.putPropertyLot(lot).stored()) {
            return false;
        }
        if (!reduce(data, record, remainder)) {
            data.removePropertyLot(lot.lotId());
            return false;
        }
        return true;
    }

    /** Removes the record, or leaves exactly the remainder of its stack under the same id. */
    private static boolean reduce(CrimeWorldData data, StolenGoodsRecord record, @Nullable ItemStack remainder) {
        if (remainder == null || remainder.isEmpty()) {
            return data.removeStolenGoods(record.transactionId()) != null;
        }
        StolenGoodsRecord rest = new StolenGoodsRecord(record.transactionId(), record.thief(), record.owner(),
                remainder.save(new CompoundTag()), 0L, record.stolenAt(), record.providerId(), record.custody());
        return data.putStolenGoods(rest).stored();
    }

    /**
     * A non-owner, non-lawful take: a new theft, filed with the original owner still named, then the
     * portion taken off the thief's record and pocketed. Capacity is checked first; the new record is
     * filed before the old one is reduced, and undone if the old one cannot be.
     */
    private static FriskRefusal steal(ServerPlayer searcher, CrimeWorldData data, StolenGoodsRecord record,
                                      UUID transferId, ItemStack portion, ItemStack remainder, long now) {
        if (!InventoryCapacity.wouldTake(searcher.getInventory(), portion)) {
            return FriskRefusal.DESTINATION_REFUSED;
        }
        Optional<TheftExecutor.TheftResult> filed;
        try {
            filed = StolenGoodsLedger.commitTheft(data, transferId, searcher.getUUID(), record.owner(), "", now,
                    StolenGoodsRecord.Custody.INVENTORY,
                    () -> new TheftExecutor.TheftResult(portion.copy(), 0L));
        } catch (RuntimeException failed) {
            McaCrime.LOGGER.error("MCA: Crime could not file the re-theft {}; nothing was taken", transferId, failed);
            return FriskRefusal.LEDGER_FULL;
        }
        if (filed.isEmpty()) {
            return FriskRefusal.LEDGER_FULL;
        }
        if (!reduce(data, record, remainder)) {
            data.removeStolenGoods(transferId);
            return FriskRefusal.LEDGER_FULL;
        }
        ItemStack pocketed = portion.copy();
        searcher.getInventory().add(pocketed);
        if (!pocketed.isEmpty()) {
            McaCrime.LOGGER.error("MCA: Crime stolen-goods take {} was accepted and then only partly taken by the "
                    + "searcher's inventory ({} left over); the remainder is dropped at their feet",
                    transferId, pocketed.getCount());
            searcher.drop(pocketed, false);
        }
        return FriskRefusal.NONE;
    }

    /** Hands the lot to its owner if they are here, and says what happened. */
    private static String deliver(@Nullable MinecraftServer server, UUID owner, PropertyLot lot, Disposition disposition) {
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(owner);
        if (player != null && player.isAlive()) {
            CrimeReconciler.deliverEscrow(player, server);
        }
        boolean held = server != null && CrimeWorldData.get(server).propertyLot(lot.lotId()) != null;
        if (disposition == Disposition.RECOVERY) {
            return held ? "mcacrime.msg.frisk.recovered_held" : "mcacrime.msg.frisk.recovered";
        }
        return "mcacrime.msg.frisk.sealed_for_owner";
    }
}
