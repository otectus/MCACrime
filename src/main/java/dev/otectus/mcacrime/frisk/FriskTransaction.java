package dev.otectus.mcacrime.frisk;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * One confiscation, start to finish (M5.2, spec §11.2).
 *
 * <p>The whole point of this class is that a stack is never in two places and never in none. The
 * order is fixed and each step is reversible until the last one:
 *
 * <ol>
 *   <li>validate all six conditions against live state, including whether the destination would
 *       take the <em>exact</em> stack that is about to be taken;</li>
 *   <li>debit the source for exactly the requested count, or nothing;</li>
 *   <li>credit the destination — escrow for a lawful seizure, the searcher's own inventory
 *       otherwise;</li>
 *   <li>if the credit fails for any reason, put the debited stack straight back;</li>
 *   <li>record the transfer id on the session so a replayed packet is recognised.</li>
 * </ol>
 *
 * <p>Steps 2 to 4 run inside one server tick with no yield, so nothing can observe the half state
 * and no save can be taken between them. The durable record is written by
 * {@link SeizureLedger} into world data — the same {@code SavedData} the custody table lives in —
 * which is why a crash cannot leave an escrowed lot without the item or an item without a lot.
 *
 * <p>Upstream has none of this. Its container moves the whole stack regardless of the requested
 * count, records nothing, checks nothing, and exposes a {@code clearContent} that empties the
 * subject's inventory outright ({@code inventory/FriskingContainer.java:69-102,130-142}).
 *
 * <p>1.21.1 note: weighing a stack against the box's bounds needs a {@link HolderLookup.Provider},
 * because a stack's components hold registry entries and serialising one needs the registry it came
 * from. The searcher's own {@code registryAccess()} is that registry, and it is threaded through
 * rather than fetched from a static, so the rule stays testable.
 */
public final class FriskTransaction {

    /** What one transfer did. */
    public record Result(FriskRefusal refusal, @Nullable UUID transferId, ItemStack moved) {

        public boolean committed() {
            return refusal == FriskRefusal.NONE;
        }

        static Result refused(FriskRefusal refusal) {
            return new Result(refusal, null, ItemStack.EMPTY);
        }
    }

    private FriskTransaction() {
    }

    /**
     * Runs one transfer.
     *
     * @param facts       the six conditions, already gathered from live state by the service
     * @param intendedCount how many the searcher asked for, already clamped by the service
     */
    public static Result commit(ServerPlayer searcher, LivingEntity subject, FriskSession session,
                                int viewIndex, int expectedRevision, int intendedCount,
                                FriskValidation.Facts facts, CrimeWorldData data, long now) {
        FriskRefusal refusal = FriskValidation.check(facts);
        if (!refusal.accepted()) {
            return Result.refused(refusal);
        }
        FriskSlotRef ref = session.slotAt(viewIndex);
        InventoryProvider provider = ref == null ? null : InventoryProviders.byId(ref.providerId());
        if (provider == null) {
            return Result.refused(FriskRefusal.SLOT_CHANGED);
        }
        UUID transferId = SeizureLedger.transferId(session.id(), viewIndex, expectedRevision,
                intendedCount);
        if (session.alreadyApplied(transferId)) {
            return Result.refused(FriskRefusal.ALREADY_DONE);
        }

        // Debit. Exactly the count asked for, or nothing at all -- there is no partial take.
        ItemStack taken = provider.extract(subject, ref, intendedCount);
        if (taken.isEmpty()) {
            return Result.refused(FriskRefusal.SLOT_CHANGED);
        }

        SeizureLedger.Outcome outcome = credit(session, searcher, subject, data, transferId,
                taken, now);
        if (!outcome.committed()) {
            // Everything from here is a rollback: the subject gets the stack back in the slot it came
            // from, or anywhere in the same inventory if that slot has since filled.
            if (!provider.restore(subject, ref, taken)) {
                // Nowhere to put it back. Dropping it at the subject's feet is the last honest option:
                // it stays theirs and it stays in the world.
                subject.spawnAtLocation(taken);
                McaCrime.LOGGER.debug("MCA: Crime frisk {} rolled back to a drop: the slot refused it",
                        transferId);
            }
            return Result.refused(outcome.refusal());
        }

        session.markApplied(transferId);
        session.stampTransfer(now);
        return new Result(FriskRefusal.NONE, transferId, taken);
    }

    /**
     * Puts the debited stack where this kind of seizure puts it, and writes it down.
     *
     * <p>Ordering is the whole correctness argument. The searcher's inventory is asked whether it
     * would take the stack <em>before</em> anything is filed, the theft is filed <em>before</em> the
     * stack is pocketed, and the supplier handed to the stolen-goods transaction mutates nothing — it
     * only reports what was already debited. So a provenance write that fails leaves the inventory
     * untouched and the caller's rollback is the complete undo. Once the stack has started going into
     * the inventory it is never rolled back: a partial add that could not be finished (which nothing
     * between the capacity check and here can cause) drops the remainder at the searcher's feet
     * rather than restoring a second copy to the subject.
     */
    private static SeizureLedger.Outcome credit(FriskSession session, ServerPlayer searcher,
                                                LivingEntity subject, CrimeWorldData data,
                                                UUID transferId, ItemStack taken, long now) {
        HolderLookup.Provider registries = searcher.registryAccess();
        if (session.seizureKind() == SeizureKind.LAWFUL_SEARCH && escrowSeizures()) {
            return SeizureLedger.lawful(data, registries, transferId, subject.getUUID(), now, taken);
        }
        if (!InventoryCapacity.wouldTake(searcher.getInventory(), taken)) {
            return SeizureLedger.Outcome.refused(FriskRefusal.DESTINATION_REFUSED);
        }
        if (session.seizureKind() == SeizureKind.CRIMINAL_SEIZURE) {
            SeizureLedger.Outcome filed = SeizureLedger.criminal(data, registries, transferId,
                    searcher.getUUID(), subject.getUUID(), now, taken::copy);
            if (!filed.committed()) {
                return filed;
            }
        }
        ItemStack pocketed = taken.copy();
        searcher.getInventory().add(pocketed);
        if (!pocketed.isEmpty()) {
            McaCrime.LOGGER.error("MCA: Crime frisk {} was accepted and then only partly taken by the "
                    + "searcher's inventory ({} left over); the remainder is dropped at their feet",
                    transferId, pocketed.getCount());
            searcher.drop(pocketed, false);
        }
        return SeizureLedger.Outcome.done(transferId);
    }

    /** Whether a lawful seizure is sealed into evidence rather than pocketed. */
    public static boolean escrowSeizures() {
        return McaCrimeConfig.COMMON.friskLawfulSeizureToEscrow.get();
    }

    /**
     * Whether the destination would take this exact stack — condition 6, asked before anything moves.
     *
     * <p>For a lawful seizure into evidence the destination is the escrow, which has its own capacity
     * and is checked when the lot is written; for every other kind it is the searcher's own inventory,
     * asked through {@link InventoryCapacity} for the whole count rather than for "some".
     */
    public static boolean destinationWouldTake(FriskSession session, ServerPlayer searcher,
                                               ItemStack probe) {
        if (session.seizureKind() == SeizureKind.LAWFUL_SEARCH && escrowSeizures()) {
            return true;
        }
        return InventoryCapacity.wouldTake(searcher.getInventory(), probe);
    }
}
