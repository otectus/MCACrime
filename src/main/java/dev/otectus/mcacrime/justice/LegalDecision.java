package dev.otectus.mcacrime.justice;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.ledger.CrimeRecord;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Local case knowledge plus independent, current grounds for detention.
 *
 * <p>{@code decree} is the royal decree in force against the offender for this jurisdiction (0.7.8),
 * when {@link Basis#ROYAL_DECREE} is a basis. It names the decree a guard's fine or arrest answers, so a
 * settlement can never be applied to a different decree than the one the challenge was about, and a
 * withdrawn decree changes the decision -- which is how a displayed offer goes stale.
 */
public record LegalDecision(UUID offender, @Nullable CrimeCommunityKey jurisdiction,
                            List<CrimeRecord> cases, Set<Basis> basis, @Nullable UUID decree) {
    public enum Basis { REPORTED_CASE, EXPLICIT_CASE, LEGACY_CASE, ESCAPED_PRISONER, HOLDING_CAPTIVE,
        WANTED, RESISTING_ARREST, MASK_WORN, ROYAL_DECREE }

    public LegalDecision {
        cases = List.copyOf(cases);
        basis = Set.copyOf(basis);
        if (decree == null && basis.contains(Basis.ROYAL_DECREE)) {
            throw new IllegalArgumentException("a ROYAL_DECREE basis must name its decree");
        }
    }

    /** A decision with no decree behind it: every decision before 0.7.8. */
    public LegalDecision(UUID offender, @Nullable CrimeCommunityKey jurisdiction, List<CrimeRecord> cases,
                         Set<Basis> basis) {
        this(offender, jurisdiction, cases, basis, null);
    }

    /** Whether the only thing behind this decision is a royal decree, with no case to settle. */
    public boolean decreeOnly() {
        return decree != null && cases.isEmpty();
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
        // Before Wanted: a decree names the village and the Crown behind it, which says more than Heat.
        if (basis.contains(Basis.ROYAL_DECREE)) return "mcacrime.challenge.reason.decree";
        if (basis.contains(Basis.WANTED)) return "mcacrime.challenge.reason.wanted";
        // Last, because a covered face is the weakest of these: it is a reason to walk over and ask,
        // never on its own a reason to detain somebody who has no charges against them.
        if (basis.contains(Basis.MASK_WORN)) return "mcacrime.challenge.reason.masked";
        return "mcacrime.challenge.no_charges";
    }
}
