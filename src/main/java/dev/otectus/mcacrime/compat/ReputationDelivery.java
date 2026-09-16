package dev.otectus.mcacrime.compat;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * What MCA: Reputation actually did with one civic write, in MCA: Crime's own vocabulary.
 *
 * <p>This replaces the {@code Optional<UUID>} the bridge used to answer with. An empty optional
 * collapsed six different answers into one — accepted but private, refused for capacity, refused
 * because the integration is off, a payload the companion could not parse, a definition it has never
 * heard of, and "the call threw and we have no idea" — and the pump then had to guess which one it
 * was. It guessed the same way every time: no id and authority held meant an unknown definition, no
 * id and authority lost meant the companion was away. Both guesses were wrong most of the time.
 *
 * <p>The outcomes below are a deliberate mirror of the companion's own receipt contract, translated
 * into plain Java so this type stays loadable with MCA: Reputation uninstalled. Only
 * {@code compat.reputation} knows what a {@code ReceiptOutcome} is; everything on this side of the
 * seam reads these names.
 *
 * @param outcome    what the companion said
 * @param incidentId the incident the write produced or replayed, when there is one to link
 * @param detail     a short, log-only note; never shown to a player and never parsed
 */
public record ReputationDelivery(Outcome outcome, Optional<UUID> incidentId, String detail) {

    public ReputationDelivery {
        outcome = outcome == null ? Outcome.UNKNOWN : outcome;
        incidentId = incidentId == null ? Optional.empty() : incidentId;
        detail = detail == null ? "" : detail;
    }

    /**
     * The answers a civic write can come back with.
     *
     * <p>Ordered from "it happened" to "we do not know". The two questions the pump asks of each one
     * are whether the work is finished and whether retrying could ever change the answer, and both are
     * properties of the outcome rather than of the call site — which is why they are methods here and
     * not a chain of {@code if}s in the pump.
     */
    public enum Outcome {

        /** Recorded, public, standing moved. The incident id is present. */
        ACCEPTED,

        /**
         * Accepted, but nothing public came of it — an unwitnessed deed whose definition keeps a
         * private record. The operation is complete and must not be retried; there is simply no public
         * incident to link, and a later resolution has nothing to move.
         */
        ACCEPTED_NO_PUBLIC_INCIDENT,

        /**
         * This exact operation had already been delivered. The id, when present, is the one the first
         * attempt produced — which is how a link lost to a crash is repaired without writing again.
         */
        DUPLICATE,

        /**
         * The community's ledger is full and could not admit another incident right now. It is not
         * "no crime occurred": the case, the Heat, and the sentence are all untouched and the write is
         * still owed. Worth retrying, because the ledger frees space as entries decay.
         */
        REFUSED_CAPACITY,

        /** The companion's own integration switch is off. Worth retrying; an operator may turn it on. */
        REFUSED_DISABLED,

        /** The payload was rejected as invalid. Retrying an identical payload cannot help. */
        REFUSED_INVALID,

        /**
         * The companion has never heard of the incident definition we named, which almost always means
         * a datapack is overriding or missing one of the files this mod ships.
         */
        UNKNOWN_INCIDENT_TYPE,

        /**
         * The incident we asked it to resolve is not there — retention dropped it, or it was absorbed
         * by a later one. Distinct from {@link #UNKNOWN_INCIDENT_TYPE} because the fix is different:
         * nothing is misconfigured, the record simply no longer exists.
         */
        MISSING_INCIDENT,

        /** No bridge at the moment: mod absent, adapter not installed, or still starting. */
        UNAVAILABLE,

        /** The call threw, or answered in a way this version does not recognise. */
        UNKNOWN;

        /** Whether the companion is finished with this operation, whatever came of it. */
        public boolean settled() {
            return this == ACCEPTED || this == ACCEPTED_NO_PUBLIC_INCIDENT || this == DUPLICATE;
        }

        /** Whether a later attempt could produce a different answer. */
        public boolean retryable() {
            return this == REFUSED_CAPACITY || this == REFUSED_DISABLED || this == UNAVAILABLE
                    || this == UNKNOWN;
        }
    }

    public static ReputationDelivery of(Outcome outcome, @Nullable UUID incidentId, String detail) {
        return new ReputationDelivery(outcome, Optional.ofNullable(incidentId), detail);
    }

    public static ReputationDelivery of(Outcome outcome, String detail) {
        return new ReputationDelivery(outcome, Optional.empty(), detail);
    }

    /** No bridge to ask. */
    public static ReputationDelivery unavailable(String detail) {
        return of(Outcome.UNAVAILABLE, detail);
    }

    /** The call threw. Deliberately retryable: a lost answer is not a refusal. */
    public static ReputationDelivery unknown(String detail) {
        return of(Outcome.UNKNOWN, detail);
    }

    /** Whether the companion is finished with this operation. */
    public boolean settled() {
        return outcome.settled();
    }
}
