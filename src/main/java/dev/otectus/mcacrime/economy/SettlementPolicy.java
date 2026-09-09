package dev.otectus.mcacrime.economy;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Decides whether a fine may be paid, which cases it answers, and what it costs (spec §6.1).
 *
 * <p>One place, because there used to be three. {@code GuardChallengeService} decided finability from
 * the flags on the open records, the case dossier quoted {@code FineCalculator}, and
 * {@code FineService} charged whatever {@code FineAllocation} summed — and none of them agreed. The
 * visible symptom was a screen offering to settle a murder for the price of the Heat behind it; the
 * underlying one is that a rule stated in three places is a rule that holds in none.
 *
 * <p>Config is passed in as {@link Settings} rather than read here, so the policy is exercisable
 * without a loaded {@code ModConfigSpec} — which is the whole reason the rule can be pinned by a
 * test at all.
 */
public final class SettlementPolicy {

    /** The settings a quote depends on, lifted out of the config so the policy stays pure. */
    public record Settings(boolean finesEnabled, int fineBase, int finePerHeat,
                           int jailableHeatThreshold, double blueFineMultiplier,
                           boolean redCanPayFine, int maxCasesPerPayment) {

        /** The live server settings. Only ever called from a path that has a loaded config. */
        public static Settings fromConfig() {
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            return new Settings(c.enableFines.get(), c.fineBase.get(), c.finePerHeat.get(),
                    c.jailableHeatThreshold.get(), c.blueFineMultiplier.get(), c.redCanPayFine.get(),
                    c.maxCasesPerFinePayment.get());
        }
    }

    private SettlementPolicy() {
    }

    /** Voluntary whole-record settlement outside a guard encounter, including unreported cases. */
    public static SettlementQuote quote(CrimeWorldData data, UUID offender, long heat, Band band, long now) {
        return quote(data, offender, heat, band, List.of(), true, now, Settings.fromConfig());
    }

    /** As above, with the cases and the settings named. */
    public static SettlementQuote quote(CrimeWorldData data, UUID offender, long heat, Band band,
                                        List<UUID> requestedCaseIds, boolean payAll, long now) {
        return quote(data, offender, heat, band, requestedCaseIds, payAll, now, Settings.fromConfig());
    }

    /**
     * Prices a settlement.
     *
     * @param requestedCaseIds exact cases to price, or empty to let the policy choose oldest-first
     * @param payAll           clear all Heat only for an automatic whole-record selection
     */
    public static SettlementQuote quote(CrimeWorldData data, UUID offender, long heat, Band band,
                                        List<UUID> requestedCaseIds, boolean payAll, long now,
                                        Settings settings) {
        if (requestedCaseIds != null && !requestedCaseIds.isEmpty())
            return quoteExact(data, offender, heat, band, requestedCaseIds, now, settings);
        if (!settings.finesEnabled()) {
            return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.DISABLED);
        }
        if (band == Band.RED && !settings.redCanPayFine()
                && (heat > 0L || !selectable(data, offender, requestedCaseIds).isEmpty()))
            return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.BARRED);
        // The eligibility gate is still FineCalculator's: jailable Heat is served, and an outlaw
        // surrenders before they pay. What the quote adds is that the price below is not this number.
        OptionalLong eligible = FineCalculator.fineFor(heat, band, settings.fineBase(),
                settings.finePerHeat(), settings.jailableHeatThreshold(), settings.blueFineMultiplier(),
                settings.redCanPayFine());
        if (eligible.isEmpty()) {
            return SettlementQuote.refused(offender, heat, heat >= settings.jailableHeatThreshold()
                    ? SettlementQuote.RejectReason.NOT_FINABLE
                    : SettlementQuote.RejectReason.BARRED);
        }

        List<CrimeRecord> selectable = selectable(data, offender, requestedCaseIds);
        List<FineAllocation.FineCase> priceable = new ArrayList<>(selectable.size());
        for (CrimeRecord record : selectable) {
            priceable.add(new FineAllocation.FineCase(record.id(), record.heatGenerated(),
                    record.fineAmount(), record.timeCommitted()));
        }
        double bandMultiplier = band == Band.BLUE ? settings.blueFineMultiplier() : 1.0D;
        FineAllocation.Allocation allocation = FineAllocation.allocate(priceable, heat,
                settings.fineBase(), settings.finePerHeat(), bandMultiplier,
                settings.maxCasesPerPayment(), payAll);

        List<SettlementQuote.CaseRef> refs = new ArrayList<>(allocation.caseIds().size());
        for (UUID caseId : allocation.caseIds()) {
            for (CrimeRecord record : selectable) {
                if (record.id().equals(caseId)) {
                    // Refused before a single emerald moves, not after: a case that must end in custody
                    // is not a more expensive fine, it is not a fine.
                    if (mandatoryCustody(record) || record.sentenceId() != null) {
                        return SettlementQuote.refused(offender, heat,
                                SettlementQuote.RejectReason.MANDATORY_CUSTODY);
                    }
                    refs.add(new SettlementQuote.CaseRef(caseId, record.resolutionRevision()));
                    break;
                }
            }
        }

        long amount = allocation.caseIds().isEmpty() && payAll
                ? eligible.getAsLong()
                : allocation.totalCost();
        if (amount <= 0L) {
            return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.NOTHING_OWED);
        }
        return new SettlementQuote(offender, refs, amount, heat, allocation.heatCleared(),
                now + SettlementQuote.VALIDITY_TICKS, Optional.empty());
    }

    /** Exact case settlement: empty means nothing, and unrelated Heat is neither priced nor cleared. */
    public static SettlementQuote quoteExact(CrimeWorldData data, UUID offender, long heat, Band band,
                                             List<UUID> caseIds, long now, Settings settings) {
        if (!settings.finesEnabled())
            return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.DISABLED);
        if (caseIds == null || caseIds.isEmpty())
            return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.NOTHING_OWED);
        if (data == null || offender == null || new java.util.HashSet<>(caseIds).size() != caseIds.size())
            return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.STALE);
        List<SettlementQuote.CaseRef> refs = new ArrayList<>();
        long cost = 0L;
        long attributable = 0L;
        for (UUID id : caseIds) {
            CrimeRecord record = data.recordById(id).orElse(null);
            if (record == null || !offender.equals(record.offender()) || !record.actionable())
                return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.STALE);
            if (mandatoryCustody(record) || record.sentenceId() != null)
                return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.MANDATORY_CUSTODY);
            refs.add(new SettlementQuote.CaseRef(id, record.resolutionRevision()));
            attributable = dev.otectus.mcacrime.util.SafeMath.addSat(attributable,
                    Math.max(0L, record.heatGenerated()));
            cost = dev.otectus.mcacrime.util.SafeMath.addSat(cost, record.fineAmount() > 0L
                    ? record.fineAmount() : FineAllocation.price(record.heatGenerated(), settings.fineBase(),
                    settings.finePerHeat(), band == Band.BLUE ? settings.blueFineMultiplier() : 1.0D));
        }
        long cleared = Math.min(Math.max(0L, heat), attributable);
        if (cleared >= settings.jailableHeatThreshold())
            return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.NOT_FINABLE);
        if (band == Band.RED && !settings.redCanPayFine())
            return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.BARRED);
        if (cost <= 0L)
            return SettlementQuote.refused(offender, heat, SettlementQuote.RejectReason.NOTHING_OWED);
        return new SettlementQuote(offender, refs, cost, heat, cleared,
                dev.otectus.mcacrime.util.SafeMath.addSat(now, SettlementQuote.VALIDITY_TICKS), Optional.empty());
    }

    /** Whether this case carries the flag that closes the money branch entirely. */
    public static boolean mandatoryCustody(CrimeRecord record) {
        return record != null
                && CrimeFlag.decode(record.context().get(CrimeContext.FLAGS))
                        .contains(CrimeFlag.MANDATORY_CUSTODY);
    }

    /**
     * The cases this payment may settle: either exactly the ones asked for, or every open case
     * oldest-first. A requested id that is not the offender's own, or is already settled, is silently
     * dropped rather than honoured — a caller must never be able to resolve somebody else's case.
     */
    private static List<CrimeRecord> selectable(CrimeWorldData data, UUID offender,
                                                List<UUID> requestedCaseIds) {
        if (data == null || offender == null) {
            return List.of();
        }
        List<CrimeRecord> out = new ArrayList<>();
        for (CrimeRecord record : data.actionableFor(offender)) {
            if (requestedCaseIds != null && !requestedCaseIds.isEmpty()
                    && !requestedCaseIds.contains(record.id())) {
                continue;
            }
            out.add(record);
        }
        return out;
    }
}
