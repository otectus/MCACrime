package dev.otectus.mcacrime.justice;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.ledger.CrimeRecord;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Local case knowledge plus independent, current grounds for detention. */
public record LegalDecision(UUID offender, @Nullable CrimeCommunityKey jurisdiction,
                            List<CrimeRecord> cases, Set<Basis> basis) {
    public enum Basis { REPORTED_CASE, EXPLICIT_CASE, LEGACY_CASE, ESCAPED_PRISONER, HOLDING_CAPTIVE,
        WANTED, RESISTING_ARREST }

    public LegalDecision {
        cases = List.copyOf(cases);
        basis = Set.copyOf(basis);
    }

    public boolean mayChallenge() { return !basis.isEmpty(); }

    public boolean requiresCustody() {
        return basis.contains(Basis.ESCAPED_PRISONER) || basis.contains(Basis.HOLDING_CAPTIVE);
    }

    public List<UUID> caseIds() { return cases.stream().map(CrimeRecord::id).toList(); }

    /** Explains a detention without inventing an offense when no local case exists. */
    public String detentionReasonKey() {
        if (basis.contains(Basis.RESISTING_ARREST)) return "mcacrime.challenge.reason.resisting";
        if (basis.contains(Basis.ESCAPED_PRISONER)) return "mcacrime.challenge.reason.escaped";
        if (basis.contains(Basis.HOLDING_CAPTIVE)) return "mcacrime.challenge.reason.captive";
        if (basis.contains(Basis.WANTED)) return "mcacrime.challenge.reason.wanted";
        return "mcacrime.challenge.no_charges";
    }
}
