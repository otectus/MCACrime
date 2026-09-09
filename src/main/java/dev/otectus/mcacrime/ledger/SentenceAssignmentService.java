package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Arrest-time assignment shared by players and NPCs. Run only on the server thread. */
public final class SentenceAssignmentService {
    private SentenceAssignmentService() { }

    /** Intake adopts durable custody identity, including recovery with no surviving arrest state. */
    public static Optional<UUID> intakeId(UUID requested, UUID assigned) {
        if (assigned != null && requested != null && !assigned.equals(requested)) return Optional.empty();
        return Optional.of(assigned != null ? assigned : requested != null ? requested : UUID.randomUUID());
    }

    /**
     * Freezes the assessed case membership after custody commits. The stored identity distinguishes
     * an intentionally empty assessment from legacy data. Replaying arrival cannot expand it.
     */
    public static boolean assign(CrimeWorldData data, UUID captive, UUID sentence,
                                 Collection<UUID> assessedCases, long now) {
        if (data == null || captive == null || sentence == null || assessedCases == null
                || !ServerMutationGate.allows(data)) return false;
        CustodyRecord held = data.getCustody(captive);
        if (held == null || !held.isLawful()) return false;
        if (held.getSentenceId() != null) return sentence.equals(held.getSentenceId());
        data.bindSentence(captive, sentence, now, assessedCases);
        held.setSentenceId(sentence);
        data.setDirty();
        return true;
    }

    /**
     * A failed intake or administrative release does not serve its cases. Remove only outstanding
     * membership so a later, valid arrest can assess them again; terminal case history is retained.
     */
    public static void cancel(CrimeWorldData data, UUID captive, UUID sentence) {
        if (data == null || sentence == null || !ServerMutationGate.allows(data)) return;
        for (CrimeRecord record : data.casesForSentence(captive, sentence)) {
            data.replaceRecord(record.withSentence(null));
        }
    }
}
