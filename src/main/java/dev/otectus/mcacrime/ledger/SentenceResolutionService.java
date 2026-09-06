package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Closes cases when a sentence is actually served, and marks them escaped when it is not.
 *
 * <p>Until this existed, {@link Resolution#SERVED} was produced nowhere in the mod. A player could be
 * jailed, serve the whole sentence, walk out — and every charge against them was still
 * {@code UNRESOLVED} and still actionable, so guards had the same legal basis to pursue them as they
 * had before the sentence started. The {@code mcacrime:sentence_served} incident shipped in the jar
 * and could never be emitted, because nothing ever reached the resolution that produces it.
 *
 * <p>Serving is not the same as paying, and the two must not be collapsed. A fine settles the cases
 * the money covered, oldest-first and bounded by what was paid. A served sentence settles the cases
 * the sentence was <em>for</em> — the ones bound to it when the prisoner was jailed, and only those
 * (0.6.0). Until membership existed this was "everything actionable at the moment of release", which
 * quietly forgave anything the prisoner did after the cell door closed.
 */
public final class SentenceResolutionService {

    private SentenceResolutionService() {
    }

    /**
     * Marks every case charged under {@code sentenceId} as {@link Resolution#SERVED}.
     *
     * <p>{@code ESCAPED} cases are included on purpose. Escaping is not forgiveness, but neither is it
     * a permanent bar: a prisoner who broke out, was caught, and then served the sentence has answered
     * for the offence, and {@link CaseTransitions} already permits {@code ESCAPED → SERVED} for exactly
     * that reason.
     *
     * @param sentenceId the sentence being closed, used as the dedupe key so a replayed release
     *                   settles nothing twice
     * @return the ids actually moved, which is empty when there was nothing open
     */
    public static List<UUID> markServed(MinecraftServer server, UUID offender, UUID sentenceId) {
        return server == null ? List.of()
                : markServed(CrimeWorldData.get(server), server.overworld().getGameTime(), offender, sentenceId,
                        CrimeCaseService.ResolutionGate.ALLOW_ALL,
                        CrimeCaseService.ResolutionSink.forServer(server));
    }

    /** {@link #markServed(MinecraftServer, UUID, UUID)} against a ledger, with the clock passed in. */
    public static List<UUID> markServed(CrimeWorldData data, long now, UUID offender, UUID sentenceId,
                                        CrimeCaseService.ResolutionGate gate) {
        return markServed(data, now, offender, sentenceId, gate, CrimeCaseService.ResolutionSink.NONE);
    }

    /** The same, announcing every case it moves through {@code sink}. */
    public static List<UUID> markServed(CrimeWorldData data, long now, UUID offender, UUID sentenceId,
                                        CrimeCaseService.ResolutionGate gate,
                                        CrimeCaseService.ResolutionSink sink) {
        return resolveActionable(data, now, offender, Resolution.SERVED, "served:" + sentenceId,
                CrimeContext.SENTENCE_ID, sentenceId, gate, sink);
    }

    /**
     * Marks every case charged under {@code sentenceId} as {@link Resolution#FINED} because
     * the remainder of the sentence was bought out.
     *
     * <p>Bail settles rather than merely ending the sentence, and it has to. A player released with
     * every charge still open would walk out of the cell into the arms of the guard who put them
     * there, which is not a mechanic, it is a loop. What distinguishes bail from serving is the
     * disposition it writes: {@code FINED} says they paid, {@code SERVED} says they did the time, and
     * the ledger keeps them apart.
     */
    public static List<UUID> markBailed(MinecraftServer server, UUID offender, UUID sentenceId) {
        return server == null ? List.of()
                : markBailed(CrimeWorldData.get(server), server.overworld().getGameTime(), offender, sentenceId,
                        CrimeCaseService.ResolutionGate.ALLOW_ALL,
                        CrimeCaseService.ResolutionSink.forServer(server));
    }

    /** {@link #markBailed(MinecraftServer, UUID, UUID)} against a ledger, with the clock passed in. */
    public static List<UUID> markBailed(CrimeWorldData data, long now, UUID offender, UUID sentenceId,
                                        CrimeCaseService.ResolutionGate gate) {
        return markBailed(data, now, offender, sentenceId, gate, CrimeCaseService.ResolutionSink.NONE);
    }

    /** The same, announcing every case it moves through {@code sink}. */
    public static List<UUID> markBailed(CrimeWorldData data, long now, UUID offender, UUID sentenceId,
                                        CrimeCaseService.ResolutionGate gate,
                                        CrimeCaseService.ResolutionSink sink) {
        return resolveActionable(data, now, offender, Resolution.FINED, "bail:" + sentenceId,
                CrimeContext.SENTENCE_ID, sentenceId, gate, sink);
    }

    /**
     * Marks every case charged under {@code sentenceId} as {@link Resolution#ESCAPED}.
     *
     * <p>Called when a prisoner breaks physical containment. This is a status change, not a
     * resolution: an escaped case stays actionable, keeps its Heat, and can still be fined, served, or
     * pardoned later. What it adds is that the ledger can now say <em>why</em> the case is still open.
     */
    public static List<UUID> markEscaped(MinecraftServer server, UUID offender, UUID sentenceId) {
        return server == null ? List.of()
                : markEscaped(CrimeWorldData.get(server), server.overworld().getGameTime(), offender, sentenceId,
                        CrimeCaseService.ResolutionGate.ALLOW_ALL,
                        CrimeCaseService.ResolutionSink.forServer(server));
    }

    /** {@link #markEscaped(MinecraftServer, UUID, UUID)} against a ledger, with the clock passed in. */
    public static List<UUID> markEscaped(CrimeWorldData data, long now, UUID offender, UUID sentenceId,
                                         CrimeCaseService.ResolutionGate gate) {
        return markEscaped(data, now, offender, sentenceId, gate, CrimeCaseService.ResolutionSink.NONE);
    }

    /** The same, announcing every case it moves through {@code sink}. */
    public static List<UUID> markEscaped(CrimeWorldData data, long now, UUID offender, UUID sentenceId,
                                         CrimeCaseService.ResolutionGate gate,
                                         CrimeCaseService.ResolutionSink sink) {
        return resolveActionable(data, now, offender, Resolution.ESCAPED, "escaped:" + sentenceId,
                CrimeContext.SENTENCE_ID, sentenceId, gate, sink);
    }

    private static List<UUID> resolveActionable(CrimeWorldData data, long now, UUID offender, Resolution target,
                                                String dedupeKey, String contextKey, UUID transactionId,
                                                CrimeCaseService.ResolutionGate gate,
                                                CrimeCaseService.ResolutionSink sink) {
        if (data == null || offender == null || transactionId == null) {
            return List.of();
        }
        // Only the cases charged under this sentence. Selecting every actionable case instead — which
        // is what this did before membership existed — meant a release settled crimes committed after
        // the prisoner was jailed, including the ones an escapee committed on the run.
        List<CrimeRecord> open = data.casesForSentence(offender, transactionId);
        if (open.isEmpty()) {
            // Either the sentence genuinely covered nothing, or it predates sentence membership and no
            // login-time inference has bound it yet. Settling the whole ledger to cover that gap would
            // be a general amnesty issued by a missing field (spec §9.2), so nothing moves.
            McaCrime.LOGGER.debug("Sentence {} for {} binds no cases; nothing to mark {}",
                    transactionId, offender, target);
            return List.of();
        }
        Map<String, String> context = Map.of(contextKey, transactionId.toString());
        List<UUID> moved = new ArrayList<>(open.size());
        for (CrimeRecord record : open) {
            // Not privileged: serving a sentence and breaking out are both ordinary gameplay. Only a
            // pardon needs the privileged flag, and neither of these is one.
            CrimeCaseService.Result result = CrimeCaseService.resolve(data, now, record.id(), target,
                    McaCrime.id(switch (target) {
                        case SERVED -> "sentence";
                        case FINED -> "bail";
                        default -> "jailbreak";
                    }),
                    dedupeKey, offender, context, false, gate, sink);
            if (result.successful()) {
                moved.add(record.id());
            }
        }
        if (!moved.isEmpty()) {
            McaCrime.LOGGER.debug("Marked {} case(s) {} for {}", moved.size(), target, offender);
        }
        return moved;
    }
}
