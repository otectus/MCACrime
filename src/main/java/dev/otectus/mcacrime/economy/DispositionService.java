package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.justice.LegalDecision;
import dev.otectus.mcacrime.state.world.CrimeWorldData;

/** Prices and revalidates a displayed local settlement without widening its assessed cases. */
public final class DispositionService {

    /**
     * One displayed settlement.
     *
     * <p>{@code decreeFine} (0.7.8) is the fine that settles a royal decree, and is offered only when the
     * decree is the whole of the decision: no case to price and no ground that demands custody. It is kept
     * beside the quote rather than folded into it, because a quote is a list of cases at revisions and a
     * decree is not a case. With cases present the quote settles them first, and the decree is answered
     * at the next challenge.
     */
    public record Offer(LegalDecision decision, SettlementQuote quote,
                        SettlementPolicy.Settings settings, Band band, String currencyId, long decreeFine) {

        public Offer(LegalDecision decision, SettlementQuote quote, SettlementPolicy.Settings settings, Band band,
                     String currencyId) {
            this(decision, quote, settings, band, currencyId, 0L);
        }

        /** Whether this offer settles a royal decree rather than cases. */
        public boolean settlesDecree() {
            return !quote.ok() && decreeFine > 0L && decision.decreeOnly() && !decision.requiresCustody();
        }

        /** Whether anything can be paid. */
        public boolean payable() {
            return quote.ok() || settlesDecree();
        }

        /** What paying costs: the quote when it stands, otherwise the decree's fine. */
        public long payableAmount() {
            return quote.ok() ? quote.amount() : settlesDecree() ? decreeFine : 0L;
        }
    }

    private DispositionService() {}

    public static Offer offer(CrimeWorldData data, LegalDecision decision, long heat, Band band,
                              long now, SettlementPolicy.Settings settings) {
        return offer(data, decision, heat, band, now, settings, "");
    }

    public static Offer offer(CrimeWorldData data, LegalDecision decision, long heat, Band band,
                              long now, SettlementPolicy.Settings settings, String currencyId) {
        return offer(data, decision, heat, band, now, settings, currencyId, 0L);
    }

    /** The same offer, with the fine a royal decree is settled by when the decision rests on one. */
    public static Offer offer(CrimeWorldData data, LegalDecision decision, long heat, Band band,
                              long now, SettlementPolicy.Settings settings, String currencyId, long decreeFine) {
        SettlementQuote quote = decision.requiresCustody()
                ? SettlementQuote.refused(decision.offender(), heat, SettlementQuote.RejectReason.MANDATORY_CUSTODY)
                : SettlementPolicy.quoteExact(data, decision.offender(), heat, band,
                        decision.caseIds(), now, settings);
        long fine = decision.decreeOnly() && settings.finesEnabled() ? Math.max(0L, decreeFine) : 0L;
        return new Offer(decision, quote, settings, band, currencyId, fine);
    }

    /** Includes penalties, flags and sentence membership, which do not increment resolutionRevision. */
    public static boolean current(Offer displayed, Offer live, long now) {
        return displayed != null && live != null && !displayed.quote().expired(now)
                && displayed.settings().equals(live.settings()) && displayed.band() == live.band()
                && java.util.Objects.equals(displayed.currencyId(), live.currencyId())
                && displayed.decision().equals(live.decision())
                && displayed.quote().heat() == live.quote().heat()
                && displayed.quote().amount() == live.quote().amount()
                && displayed.quote().heatCleared() == live.quote().heatCleared()
                && displayed.quote().reject().equals(live.quote().reject())
                && displayed.decreeFine() == live.decreeFine();
    }
}
