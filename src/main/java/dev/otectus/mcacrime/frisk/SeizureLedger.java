package dev.otectus.mcacrime.frisk;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.mug.npc.StolenGoodsLedger;
import dev.otectus.mcacrime.mug.npc.TheftExecutor;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.PropertyLot;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Where a seized stack is written down (M5.3, spec §11.3).
 *
 * <p>Three kinds of seizure, three ledgers, and no stack in more than one of them — which is the
 * whole reason this class exists rather than a branch inside the transaction. The specification's
 * complaint about the upstream screen is exactly that it treats a guard's search, a voluntary
 * handover and a robbery as the same event; here they are three, and each is recorded once.
 *
 * <ul>
 *   <li><b>Lawful search</b> puts the stack in the property escrow as a lot owed back to the
 *       subject. The item does <em>not</em> also go into the box, because one stack cannot be in two
 *       places: the escrow is where it is, and the existing recovery ledger is what returns it when
 *       custody ends, bail is paid or a pardon lands. That is §11.3's "a possession box is not an
 *       excuse to bypass the existing recovery ledger", made structural.</li>
 *   <li><b>Criminal seizure</b> puts the stack in the box and files the theft through the existing
 *       two-phase stolen-goods transaction, so provenance exists, witnesses can act on it, and the
 *       recovery path can take it back.</li>
 *   <li><b>Voluntary transfer</b> puts the stack in the box and writes nothing. There is no loss to
 *       record.</li>
 * </ul>
 */
public final class SeizureLedger {

    private SeizureLedger() {
    }

    /** What happened to one stack. */
    public record Outcome(boolean committed, @Nullable UUID recordId, FriskRefusal refusal) {

        public static Outcome refused(FriskRefusal refusal) {
            return new Outcome(false, null, refusal);
        }

        public static Outcome done(@Nullable UUID recordId) {
            return new Outcome(true, recordId, FriskRefusal.NONE);
        }
    }

    /**
     * A stable id for one transfer.
     *
     * <p>Derived rather than random, so the same transfer attempted twice produces the same id and
     * the session's applied-set can recognise the replay. It names the session, the slot and the
     * revision, which together identify "this take of this stack" uniquely within one search.
     */
    public static UUID transferId(long sessionId, int viewIndex, int revision, int count) {
        return UUID.nameUUIDFromBytes(("frisk:" + sessionId + ":" + viewIndex + ":" + revision + ":"
                + count).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Records a lawful seizure of a stack the caller has already removed.
     *
     * <p>Takes the stack rather than a supplier, because the escrow can refuse: the caller holds the
     * only reference to what was removed and is the only thing that can put it back. A refusal here
     * is always paired with a restore at the call site, which is why {@link FriskTransaction} runs
     * the removal and this does not.
     */
    public static Outcome lawful(CrimeWorldData data, UUID transferId, UUID subject, long now,
                                 ItemStack taken) {
        if (data == null || !ServerMutationGate.allows(data) || subject == null) {
            return Outcome.refused(FriskRefusal.LEDGER_FULL);
        }
        if (taken == null || taken.isEmpty()) {
            return Outcome.refused(FriskRefusal.SLOT_CHANGED);
        }
        PropertyLot lot = PropertyLot.ofStack(transferId, subject, taken, transferId, now);
        if (!data.putPropertyLot(lot).stored()) {
            // The escrow is full. Nothing is recorded, so the caller puts the stack straight back:
            // taking something the ledger cannot remember is how property quietly disappears.
            return new Outcome(false, null, FriskRefusal.LEDGER_FULL);
        }
        return Outcome.done(transferId);
    }

    /**
     * Records a criminal seizure through the existing stolen-goods transaction.
     *
     * <p>{@code commitTheft} reserves capacity, runs the removal and files provenance in one call,
     * and releases the reservation whatever happens — so a removal that succeeds and a record that
     * cannot be stored is an exception rather than an untracked theft.
     */
    public static Outcome criminal(CrimeWorldData data, UUID transferId, UUID thief, UUID owner,
                                   long now, Supplier<ItemStack> remove) {
        if (data == null || !ServerMutationGate.allows(data) || thief == null || owner == null) {
            return Outcome.refused(FriskRefusal.LEDGER_FULL);
        }
        try {
            Optional<TheftExecutor.TheftResult> result = StolenGoodsLedger.commitTheft(
                    data, transferId, thief, owner, "", now,
                    () -> {
                        ItemStack taken = remove.get();
                        return taken == null || taken.isEmpty()
                                ? TheftExecutor.TheftResult.nothing()
                                : new TheftExecutor.TheftResult(taken, 0L);
                    });
            if (result.isEmpty()) {
                return Outcome.refused(FriskRefusal.LEDGER_FULL);
            }
            return result.get().tookSomething() ? Outcome.done(transferId)
                    : Outcome.refused(FriskRefusal.SLOT_CHANGED);
        } catch (RuntimeException failed) {
            McaCrime.LOGGER.error("MCA: Crime could not file a frisk theft {}; nothing was taken",
                    transferId, failed);
            return Outcome.refused(FriskRefusal.LEDGER_FULL);
        }
    }
}
