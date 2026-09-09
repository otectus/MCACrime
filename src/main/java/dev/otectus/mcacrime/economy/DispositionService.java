package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.justice.LegalDecision;
import dev.otectus.mcacrime.state.world.CrimeWorldData;

/** Prices and revalidates a displayed local settlement without widening its assessed cases. */
public final class DispositionService {
    public record Offer(LegalDecision decision, SettlementQuote quote,
                        SettlementPolicy.Settings settings, Band band, String currencyId) {}

    private DispositionService() {}

    public static Offer offer(CrimeWorldData data, LegalDecision decision, long heat, Band band,
                              long now, SettlementPolicy.Settings settings) {
        return offer(data, decision, heat, band, now, settings, "");
    }

    public static Offer offer(CrimeWorldData data, LegalDecision decision, long heat, Band band,
                              long now, SettlementPolicy.Settings settings, String currencyId) {
        SettlementQuote quote = decision.requiresCustody()
                ? SettlementQuote.refused(decision.offender(), heat, SettlementQuote.RejectReason.MANDATORY_CUSTODY)
                : SettlementPolicy.quoteExact(data, decision.offender(), heat, band,
                        decision.caseIds(), now, settings);
        return new Offer(decision, quote, settings, band, currencyId);
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
                && displayed.quote().reject().equals(live.quote().reject());
    }
}
