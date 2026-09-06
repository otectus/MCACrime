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
     * A veto consulted after the transition table has accepted a change and before anything is
     * written.
     *
     * <p>It exists because the only refusal this service could express was structural — the table
     * says that disposition cannot follow this one — and callers increasingly need a second kind: a
     * policy refusal that must happen <em>before</em> money moves or a record is rewritten. Passing it
     * in rather than hard-coding it also means the seam is a lambda in a test, which is the difference
     * between an assertion about resolution ordering and a running server.
     */
    @FunctionalInterface
    public interface ResolutionGate {

        /** Allows every transition the table already permits: the behaviour before the gate existed. */
        ResolutionGate ALLOW_ALL = (record, target) -> true;

        boolean allow(CrimeRecord record, Resolution target);
    }

    /** One accepted disposition change, as everything outside the ledger needs to see it. */
    public record Resolved(CrimeRecordView before, CrimeRecordView after, CrimeResolutionEntry entry) {
    }

    /**
     * Where a case that genuinely moved is announced.
     *
     * <p>The companion outbox and the public event are the two things a settled case owes the world,
     * and neither can run against a ledger with no server behind it. Passing them in as a sink rather
     * than reaching for {@link NeoForge#EVENT_BUS} inside the write is what lets a caller that only
     * has a {@link CrimeWorldData} still be the one that decides they happen — and what lets a test
     * count them, which is the difference between "the fine settled the case" and "the fine settled
     * the case and said so".
     */
    @FunctionalInterface
    public interface ResolutionSink {

        /** Announces nothing: the right sink for a ledger with no server behind it. */
        ResolutionSink NONE = resolved -> {
        };

        void announce(Resolved resolved);

        /** The live sink: the integration outbox, then the event, in that order. */
        static ResolutionSink forServer(@Nullable MinecraftServer server) {
            if (server == null) {
                return NONE;
            }
            return resolved -> {
                // Queued before the event is posted, so the authoritative change and the work it owes
                // the companion mod land in the same dirty cycle.
                CrimeIntegrationHooks.onResolved(server, resolved.after(), resolved.entry());

                ServerPlayer offender = server.getPlayerList().getPlayer(resolved.after().offenderId());
                if (offender != null) {
                    NeoForge.EVENT_BUS.post(new CrimeRecordResolvedEvent(offender,
                            resolved.before(), resolved.after(), resolved.entry()));
                }
            };
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
        return resolve(CrimeWorldData.get(server), server.overworld().getGameTime(), recordId, target,
                source, dedupeKey, actorId, context, privileged, ResolutionGate.ALLOW_ALL,
                ResolutionSink.forServer(server));
    }

    /**
     * The same transition against a ledger, with no server behind it.
     *
     * <p>What the server overload adds is exactly what only a server can do: read the world clock,
     * queue the integration outbox entry, and post the resolved event. The decision — table, gate,
     * revision, history — is all here, so it is testable without one.
     *
     * @param gameTime the tick stamped on the resolution entry; the caller owns the clock
     * @param gate     consulted after the table and before the write
     */
    public static Result resolve(CrimeWorldData data, long gameTime, UUID recordId, Resolution target,
                                 ResourceLocation source, String dedupeKey,
                                 @Nullable UUID actorId, Map<String, String> context,
                                 boolean privileged, ResolutionGate gate) {
        return resolve(data, gameTime, recordId, target, source, dedupeKey, actorId, context, privileged,
                gate, ResolutionSink.NONE);
    }

    /**
     * The same transition, announcing itself through {@code sink} when it actually moved.
     *
     * <p>A server-bound caller passes {@link ResolutionSink#forServer}; the ledger-only overload above
     * passes {@link ResolutionSink#NONE}. The distinction is not decoration. Both {@code FineService}
     * and {@code SentenceResolutionService} settle cases through the ledger overload, and for one
     * release that silently meant a paid fine and a served sentence notified nobody — no outbox entry
     * for the companion mod, no {@link CrimeRecordResolvedEvent} for anyone listening.
     *
     * @param sink consulted once per case that genuinely changed disposition, never for a duplicate,
     *             a refusal, or a failed write
     */
    public static Result resolve(CrimeWorldData data, long gameTime, UUID recordId, Resolution target,
                                 ResourceLocation source, String dedupeKey,
                                 @Nullable UUID actorId, Map<String, String> context,
                                 boolean privileged, ResolutionGate gate, ResolutionSink sink) {
        Applied applied = apply(data, gameTime, recordId, target, source, dedupeKey, actorId, context,
                privileged, gate);
        if (applied.entry() != null && sink != null) {
            sink.announce(new Resolved(applied.before().view(), applied.after().view(), applied.entry()));
        }
        return applied.result();
    }

    /**
     * The applied change, or just its refusal. {@code entry} is null for every outcome that wrote
     * nothing, which is what tells the server overload whether there is anything to announce.
     */
    private record Applied(Result result, @Nullable CrimeRecord before, @Nullable CrimeRecord after,
                           @Nullable CrimeResolutionEntry entry) {

        static Applied refused(Result result) {
            return new Applied(result, null, null, null);
        }
    }

    private static Applied apply(CrimeWorldData data, long gameTime, UUID recordId, Resolution target,
                                 ResourceLocation source, String dedupeKey,
                                 @Nullable UUID actorId, Map<String, String> context,
                                 boolean privileged, ResolutionGate gate) {
        if (data == null || recordId == null || target == null) {
            return Applied.refused(Result.of(CrimeMutationStatus.NO_MATCH));
        }
        Optional<CrimeRecord> existing = data.recordById(recordId);
        if (existing.isEmpty()) {
            return Applied.refused(Result.of(CrimeMutationStatus.NO_MATCH));
        }
        CrimeRecord before = existing.get();

        CaseTransitions.Outcome outcome =
                CaseTransitions.classify(before.resolution(), target, privileged);
        if (outcome == CaseTransitions.Outcome.DUPLICATE) {
            // Already there. A replayed transaction must read as success, or a retrying outbox turns
            // one settled case into a stream of errors.
            return Applied.refused(Result.of(CrimeMutationStatus.DUPLICATE, before.view()));
        }
        if (outcome == CaseTransitions.Outcome.REJECTED) {
            return Applied.refused(Result.of(
                    privileged ? CrimeMutationStatus.INVALID_STATE : CrimeMutationStatus.NOT_ALLOWED,
                    before.view()));
        }
        if (gate != null && !gate.allow(before, target)) {
            return Applied.refused(Result.of(CrimeMutationStatus.NOT_ALLOWED, before.view()));
        }

        CrimeResolutionEntry entry = new CrimeResolutionEntry(before.resolutionRevision() + 1L, target,
                source, dedupeKey, gameTime, actorId, context);
        CrimeRecord after = before.withResolution(target, entry);

        if (!data.replaceRecord(after)) {
            return Applied.refused(Result.of(CrimeMutationStatus.ERROR, before.view()));
        }
        return new Applied(Result.of(CrimeMutationStatus.APPLIED, after.view()), before, after, entry);
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
