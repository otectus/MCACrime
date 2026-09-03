package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.api.event.CrimeRecordResolvedEvent;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.api.result.CrimeMutationStatus;
import dev.otectus.mcacrime.integration.CrimeIntegrationHooks;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place a case's disposition changes.
 *
 * <p>Fines, sentences, pardons, escapes, and quest rewards all arrive here rather than editing the
 * ledger themselves. That is what makes the transition table, the revision counter, the outbox entry,
 * and the resolved event impossible to forget: they are not five things every caller has to remember,
 * they are one method.
 *
 * <p>Every mutation takes an <b>exact record id</b>. A selector may be used to decide which case to
 * act on, but it is resolved to an id first, by the caller, and only the id crosses into here. The
 * difference matters: a quest reward that passed a filter instead of an id would pardon everything
 * matching it the day somebody wrote a filter slightly too wide.
 */
public final class CrimeCaseService {

    private CrimeCaseService() {
    }

    /** The outcome of a disposition change: what happened, and the case as it now stands. */
    public record Result(CrimeMutationStatus status, Optional<CrimeRecordView> record) {

        static Result of(CrimeMutationStatus status) {
            return new Result(status, Optional.empty());
        }

        static Result of(CrimeMutationStatus status, CrimeRecordView view) {
            return new Result(status, Optional.of(view));
        }

        public boolean successful() {
            return status.successful();
        }
    }

    /**
     * Moves one case to a new disposition.
     *
     * @param privileged true only for an explicit administrative or pardon transaction; ordinary
     *                   gameplay must pass false, which is what stops a reward from wiping a murder
     */
    public static Result resolve(MinecraftServer server, UUID recordId, Resolution target,
                                 ResourceLocation source, String dedupeKey,
                                 @Nullable UUID actorId, Map<String, String> context,
                                 boolean privileged) {
        if (server == null || recordId == null || target == null) {
            return Result.of(CrimeMutationStatus.NO_MATCH);
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        Optional<CrimeRecord> existing = data.recordById(recordId);
        if (existing.isEmpty()) {
            return Result.of(CrimeMutationStatus.NO_MATCH);
        }
        CrimeRecord before = existing.get();

        CaseTransitions.Outcome outcome =
                CaseTransitions.classify(before.resolution(), target, privileged);
        if (outcome == CaseTransitions.Outcome.DUPLICATE) {
            // Already there. A replayed transaction must read as success, or a retrying outbox turns
            // one settled case into a stream of errors.
            return Result.of(CrimeMutationStatus.DUPLICATE, before.view());
        }
        if (outcome == CaseTransitions.Outcome.REJECTED) {
            return Result.of(privileged ? CrimeMutationStatus.INVALID_STATE : CrimeMutationStatus.NOT_ALLOWED,
                    before.view());
        }

        long gameTime = server.overworld().getGameTime();
        CrimeResolutionEntry entry = new CrimeResolutionEntry(before.resolutionRevision() + 1L, target,
                source, dedupeKey, gameTime, actorId, context);
        CrimeRecord after = before.withResolution(target, entry);

        if (!data.replaceRecord(after)) {
            return Result.of(CrimeMutationStatus.ERROR, before.view());
        }
        // Queued before the event is posted, so the authoritative change and the work it owes the
        // companion mod land in the same dirty cycle.
        CrimeIntegrationHooks.onResolved(server, after.view(), entry);

        ServerPlayer offender = server.getPlayerList().getPlayer(after.offender());
        if (offender != null) {
            NeoForge.EVENT_BUS.post(
                    new CrimeRecordResolvedEvent(offender, before.view(), after.view(), entry));
        }
        return Result.of(CrimeMutationStatus.APPLIED, after.view());
    }

    /**
     * Records which civic incident a case produced in a companion mod.
     *
     * <p>Idempotent by design: re-linking the same incident is a no-op, and re-linking a
     * <em>different</em> one is refused. The second case is the dangerous one — it would mean two
     * incidents exist for one crime, and quietly overwriting the link would hide the duplicate rather
     * than surface it.
     */
    public static Result linkReputationIncident(MinecraftServer server, UUID recordId, UUID incidentId) {
        if (server == null || recordId == null || incidentId == null) {
            return Result.of(CrimeMutationStatus.NO_MATCH);
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        Optional<CrimeRecord> existing = data.recordById(recordId);
        if (existing.isEmpty()) {
            return Result.of(CrimeMutationStatus.NO_MATCH);
        }
        CrimeRecord record = existing.get();
        Optional<UUID> current = record.reputationIncident();
        if (current.isPresent()) {
            return current.get().equals(incidentId)
                    ? Result.of(CrimeMutationStatus.DUPLICATE, record.view())
                    : Result.of(CrimeMutationStatus.INVALID_STATE, record.view());
        }
        CrimeRecord linked = record.withReputationIncident(incidentId);
        return data.replaceRecord(linked)
                ? Result.of(CrimeMutationStatus.APPLIED, linked.view())
                : Result.of(CrimeMutationStatus.ERROR, record.view());
    }

    /** Fills in the assessed fine and sentence for a case. */
    public static Result assignPenalty(MinecraftServer server, UUID recordId, long fineAmount, long jailTicks) {
        if (server == null || recordId == null) {
            return Result.of(CrimeMutationStatus.NO_MATCH);
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        Optional<CrimeRecord> existing = data.recordById(recordId);
        if (existing.isEmpty()) {
            return Result.of(CrimeMutationStatus.NO_MATCH);
        }
        CrimeRecord updated = existing.get().withPenalty(fineAmount, jailTicks);
        return data.replaceRecord(updated)
                ? Result.of(CrimeMutationStatus.APPLIED, updated.view())
                : Result.of(CrimeMutationStatus.ERROR, existing.get().view());
    }

    /** The public projection of one case, or empty. */
    public static Optional<CrimeRecordView> view(MinecraftServer server, UUID recordId) {
        if (server == null || recordId == null) {
            return Optional.empty();
        }
        return CrimeWorldData.get(server).recordById(recordId).map(CrimeRecord::view);
    }
}
