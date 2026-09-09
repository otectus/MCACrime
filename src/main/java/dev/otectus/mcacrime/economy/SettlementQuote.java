package dev.otectus.mcacrime.economy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A priced offer to settle named cases, valid for a short window (spec §6.1).
 *
 * <p>It exists because a fine used to be priced twice: once by the guard screen or the dossier, to
 * show the player a number, and again inside the payment, to decide what to charge. The two used
 * different rules — the screen quoted {@code FineCalculator}'s whole-Heat price, the payment charged
 * the sum of the cases it actually settled — so the figure the player agreed to and the figure taken
 * from them were only ever coincidentally the same. A quote is the one price, produced once,
 * displayed and then charged.
 *
 * <p>The {@link CaseRef} revisions are the staleness check. {@code CrimeRecord} already carries a
 * {@code resolutionRevision} that moves on every disposition change, so a quote can be refused when
 * one of the cases it priced has been settled, pardoned, or escaped between the offer and the click,
 * rather than charging for a case that is no longer there to close.
 */
public record SettlementQuote(UUID offender, List<CaseRef> cases, long amount, long heat,
                              long heatCleared, long expiresAt, Optional<RejectReason> reject) {

    /** How long an offer stands: thirty seconds. */
    public static final long VALIDITY_TICKS = 600L;

    /** One case in the offer, at the revision it was priced under. */
    public record CaseRef(UUID id, long revision) {
    }

    /** Why no offer could be made. Each maps to a message the player already knows. */
    public enum RejectReason {
        /** Fines are switched off on this server. */
        DISABLED("mcacrime.fine.disabled"),
        /** Heat is at or above the jailable threshold: this is served, not paid. */
        NOT_FINABLE("mcacrime.fine.notfinable"),
        /** An outlaw who has not surrendered yet. */
        BARRED("mcacrime.fine.barred"),
        /**
         * A selected case is flagged {@code MANDATORY_CUSTODY}. The same message as
         * {@link #NOT_FINABLE}, because from the player's side it is the same answer: this one cannot
         * be bought off.
         */
        MANDATORY_CUSTODY("mcacrime.fine.notfinable"),
        /** Nothing outstanding to settle. */
        NOTHING_OWED("mcacrime.fine.nothing"),
        /** The offer stood too long, or a case moved under it. Re-quote and ask again. */
        STALE("mcacrime.fine.stale");

        private final String messageKey;

        RejectReason(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    public SettlementQuote {
        cases = cases == null ? List.of() : List.copyOf(cases);
        reject = reject == null ? Optional.empty() : reject;
    }

    static SettlementQuote refused(UUID offender, long heat, RejectReason reason) {
        return new SettlementQuote(offender, List.of(), 0L, heat, 0L, 0L, Optional.of(reason));
    }

    /** Whether there is a price to charge. */
    public boolean ok() {
        return reject.isEmpty();
    }

    public boolean expired(long now) {
        return ok() && now > expiresAt;
    }

    /** The ids this offer settles, in the order it prices them. */
    public List<UUID> caseIds() {
        return cases.stream().map(CaseRef::id).toList();
    }
}
