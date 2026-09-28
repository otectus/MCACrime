package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.event.ExecutionCarriedOutEvent;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.detention.ExecutionAuthorization;
import dev.otectus.mcacrime.incident.IncidentNotifications;
import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.PropertyLot;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything that happens once, after a confirmed execution (0.7.5 §3.19, M6.8).
 *
 * <p>"Once" is the whole design. A guillotine can be reloaded mid-swing, a damage event can be
 * cancelled by a totem or a revive mod, and a chunk can unload between the blade and the death — so
 * this runs behind a completion set keyed by the sentence, and a second call for the same sentence
 * does nothing at all. A <b>cancelled</b> death never reaches here: the device confirms the subject
 * is dead before it calls, which is why "totem, PlayerRevive or a cancelled damage event resolves
 * nothing and leaves the sentence live" is true by construction rather than by a check.
 *
 * <p>What it does, in order: closes the cases the sentence was for, resolves the warrant, evaluates
 * the bounty, deals with the possessions, and fires one event. What it deliberately does <b>not</b>
 * do:
 *
 * <ul>
 *   <li><b>Kill anybody.</b> The death has already happened, through the ordinary damage pipeline
 *       with the actor attributed.</li>
 *   <li><b>Touch MCA's own death handling.</b> A villager dies permanently and MCA owns the corpse,
 *       the family notification and the memorial; {@code compat/mca/McaBinding} exposes no death or
 *       family member at all, so nothing here claims one. The existing relationship and grief paths
 *       run off the ordinary death they always have.</li>
 *   <li><b>Change a player's respawn.</b> A condemned player dies an ordinary vanilla death and
 *       respawns with no lingering sentence — the custody and the jail state are both closed here.</li>
 * </ul>
 */
public final class CapitalDeathOutcome {

    /** Sentences already settled by an execution, so the whole outcome runs exactly once. */
    private static final Set<UUID> COMPLETED = java.util.Collections.synchronizedSet(new HashSet<>());

    /** What one call actually did, for the log, the tests and the diagnostics command. */
    public record Result(boolean ran, int casesClosed, boolean warrantResolved, int lotsDropped,
                         boolean possessionsBanked) {

        static Result nothing() {
            return new Result(false, 0, false, 0, false);
        }
    }

    private CapitalDeathOutcome() {
    }

    /**
     * Settles a carried-out capital sentence.
     *
     * @param subject who died; may already be removed from the world
     * @param actor   who carried it out, for the event and the log
     * @param at      where the device is, for anything that has to be dropped
     */
    public static Result onExecuted(@Nullable MinecraftServer server, @Nullable LivingEntity subject,
                                    @Nullable UUID actor, @Nullable BlockPos at) {
        if (server == null || subject == null || !ServerMutationGate.allows(server)) {
            return Result.nothing();
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        if (data == null) {
            return Result.nothing();
        }
        UUID subjectId = subject.getUUID();
        CustodyRecord custody = data.getCustody(subjectId);
        UUID sentenceId = custody == null ? null : custody.getSentenceId();
        if (sentenceId == null) {
            // Nothing to close. The death itself has already happened; an execution with no sentence
            // behind it is a bug elsewhere, and inventing a settlement here would hide it.
            McaCrime.LOGGER.warn("MCA: Crime - {} died at a device under no recorded sentence; nothing "
                    + "was settled", subjectId);
            return Result.nothing();
        }
        if (!claim(sentenceId)) {
            return Result.nothing(); // already settled; a replayed blow settles nothing twice
        }

        // 1. The cases the sentence was for. SERVED rather than a new disposition: the sentence was
        //    carried out, which is what serving one means, and the ledger already knows that word.
        List<UUID> closed = SentenceResolutionService.markServed(server, subjectId, sentenceId);

        // 2. The warrant. An executed offender is not still wanted.
        boolean warrantResolved = resolveWarrant(data, subjectId, server.overworld().getGameTime());

        // 3. The bounty. Evaluated through the ordinary kill route, which pays only a claimant with
        //    an open warrant and a lawful killing; an execution by the state pays nobody, and that is
        //    the correct outcome rather than a missing feature.
        // (The kill route runs on the damage event itself; nothing is re-paid here.)

        // 4. The possessions.
        boolean drop = CapitalSentenceService.dropsPossessions();
        int dropped = drop ? spill(server, subject, data, subjectId, at) : 0;

        // 5. The sentence kind stays on the record: it is what the sentence was. The execution order
        //    is spent, and the custody has already been ended by the bridge.
        ExecutionAuthorization.clear(subjectId, ExecutionAuthorization.ClearReason.CARRIED_OUT);

        IncidentNotifications.post(new ExecutionCarriedOutEvent(subjectId, subject instanceof Player,
                actor, sentenceId, ReleaseReason.EXECUTED.name().toLowerCase(java.util.Locale.ROOT),
                closed.size(), drop));
        McaCrime.LOGGER.info("MCA: Crime - the capital sentence {} against {} was carried out: {} case(s)"
                + " closed, warrant {}, possessions {}", sentenceId, subjectId, closed.size(),
                warrantResolved ? "resolved" : "already closed", drop ? "dropped" : "banked");
        return new Result(true, closed.size(), warrantResolved, dropped, !drop);
    }

    /**
     * Takes the right to settle one sentence, once.
     *
     * <p>Separated from the settlement itself so the once-only rule is assertable without a server:
     * it is the single guarantee standing between a reloaded device, a replayed damage event and a
     * prisoner's estate being handed out twice.
     *
     * @return true for the first caller and false for every caller after it
     */
    public static boolean claim(@Nullable UUID sentenceId) {
        if (sentenceId == null) {
            return false;
        }
        synchronized (COMPLETED) {
            return COMPLETED.add(sentenceId);
        }
    }

    /** Whether this sentence has already been settled by an execution. */
    public static boolean settled(@Nullable UUID sentenceId) {
        return sentenceId != null && COMPLETED.contains(sentenceId);
    }

    /** Forgets the completion set. Server stop, and every test's setup. */
    public static void clearAll() {
        COMPLETED.clear();
    }

    /**
     * Closes the offender's open warrant.
     *
     * <p>Through the ordinary warrant table rather than a bespoke path: a warrant is closed when the
     * cases behind it are, and this simply makes sure nobody is left pursuing a dead offender.
     */
    private static boolean resolveWarrant(CrimeWorldData data, UUID subject, long now) {
        Warrant warrant = data.warrant(subject);
        if (warrant == null || !warrant.open()) {
            return false;
        }
        // Closed rather than deleted, so a stale bounty claim key still resolves to a closed warrant
        // instead of to nothing at all.
        return data.putWarrant(warrant.closed(now)).stored();
    }

    /**
     * Spills whatever the law was holding for this prisoner, at the device.
     *
     * <p>The escrow is where a lawful search puts what it took, and an executed prisoner is never
     * coming back for it. Dropping it at the device is the visible, recoverable outcome; banking it —
     * {@code dropPossessionsOnExecution = false} — simply leaves the lots where they are, so an
     * operator can hand them to a family or to the village.
     */
    private static int spill(MinecraftServer server, LivingEntity subject, CrimeWorldData data,
                             UUID subjectId, @Nullable BlockPos at) {
        if (!(subject.level() instanceof ServerLevel level)) {
            return 0;
        }
        BlockPos where = at == null ? subject.blockPosition() : at;
        List<PropertyLot> lots = new ArrayList<>(data.propertyEscrowFor(subjectId));
        int dropped = 0;
        for (PropertyLot lot : lots) {
            // 1.21.1: a saved stack decodes only with a registry lookup, because its components hold
            // registry references. Same lot, same drop.
            ItemStack stack = lot.stack(level.registryAccess());
            if (stack != null && !stack.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(level, where.getX() + 0.5D,
                        where.getY() + 0.5D, where.getZ() + 0.5D, stack);
                dropped++;
            }
            data.removePropertyLot(lot.lotId());
        }
        return dropped;
    }

    /** Every settled sentence, for the diagnostics command. */
    public static Map<UUID, Boolean> completed() {
        synchronized (COMPLETED) {
            Map<UUID, Boolean> out = new java.util.LinkedHashMap<>();
            COMPLETED.forEach(id -> out.put(id, Boolean.TRUE));
            return Map.copyOf(out);
        }
    }
}
