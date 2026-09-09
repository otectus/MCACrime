package dev.otectus.mcacrime;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.DispositionService;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import dev.otectus.mcacrime.enforcement.GuardChallenge;
import dev.otectus.mcacrime.justice.JusticeService;
import dev.otectus.mcacrime.justice.LegalDecision;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LocalJusticeSettlementTest {
    private static final UUID OFFENDER = UUID.randomUUID();
    private static final ResourceLocation THEFT = new ResourceLocation("mcacrime", "theft");
    private static final CrimeCommunityKey LOCAL = new CrimeCommunityKey(new ResourceLocation("minecraft", "overworld"), 1);
    private static final CrimeCommunityKey REMOTE = new CrimeCommunityKey(new ResourceLocation("minecraft", "overworld"), 2);
    private static final JusticeService.Settings EVIDENCE = new JusticeService.Settings(true, false, .75);
    private static final SettlementPolicy.Settings FINES = new SettlementPolicy.Settings(true, 10, 2, 100, .5, true, 8);

    private CrimeRecord record(long time, long heat, long price) {
        return new CrimeRecord(UUID.randomUUID(), OFFENDER, null, THEFT, OptionalInt.empty(),
                LOCAL, true, Set.of(), time, heat, -5L, price, 0L, Resolution.UNRESOLVED,
                0L, List.of(), null, Map.of());
    }

    private CrimeWorldData ledger(CrimeRecord... records) {
        CrimeWorldData data = new CrimeWorldData();
        for (var record : records) data.addRecord(record);
        return data;
    }

    private void report(CrimeWorldData data, CrimeRecord record, CrimeCommunityKey jurisdiction, float confidence) {
        data.addReport(new CrimeReport(UUID.randomUUID(), record.id(), UUID.randomUUID(), UUID.randomUUID(),
                OFFENDER, THEFT, jurisdiction, 1L, 500L, confidence, true));
    }

    private LegalDecision decision(CrimeWorldData data, long now) {
        return JusticeService.evaluate(data, OFFENDER, LOCAL, now, EVIDENCE, false, false);
    }

    private DispositionService.Offer offer(CrimeWorldData data, long heat, long now) {
        return DispositionService.offer(data, decision(data, now), heat, Band.GREY, now, FINES);
    }

    @Test void localReviewQuoteAndDebitAnswerOnlyTheReportedCase() {
        var known = record(1, 5, 23);
        var privateCase = record(2, 200, 500).withContext(CrimeContext.FLAGS, "mandatory_custody");
        var remote = record(3, 30, 90);
        var data = ledger(known, privateCase, remote);
        report(data, known, LOCAL, 1F);
        report(data, remote, REMOTE, 1F);
        var offer = offer(data, 235L, 10L);
        assertEquals(List.of(known.id()), offer.decision().caseIds());
        assertEquals(List.of(known.id()), offer.quote().caseIds());
        assertEquals(Set.of(LegalDecision.Basis.REPORTED_CASE), offer.decision().basis());
        assertTrue(offer.quote().ok());
        AtomicLong debit = new AtomicLong();
        var paid = FineService.pay(data, 10L, offer.quote(), amount -> { debit.addAndGet(amount); return true; },
                CrimeCaseService.ResolutionGate.ALLOW_ALL);
        assertTrue(paid.paid());
        assertEquals(23L, debit.get());
        assertEquals(230L, paid.newHeat());
        assertEquals(List.of(known.id()), paid.settledCaseIds());
        assertTrue(data.recordById(privateCase.id()).orElseThrow().actionable());
        assertTrue(data.recordById(remote.id()).orElseThrow().actionable());
        assertFalse(decision(data, 11L).mayChallenge());
    }

    @Test void privateWitnessFlagAndAnonymousOrWeakReportsGrantNoAuthority() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        assertFalse(decision(data, 10L).mayChallenge());
        report(data, crime, LOCAL, .4F);
        data.addReport(new CrimeReport(UUID.randomUUID(), crime.id(), UUID.randomUUID(), UUID.randomUUID(),
                null, THEFT, LOCAL, 1L, 500L, 1F, true));
        assertFalse(decision(data, 10L).mayChallenge());
    }

    @Test void reportExpiryAndResolutionRemoveLocalAuthority() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        report(data, crime, LOCAL, 1F);
        assertTrue(decision(data, 499L).mayChallenge());
        assertFalse(decision(data, 500L).mayChallenge());
        CrimeCaseService.resolve(data, 100L, crime.id(), Resolution.PARDONED, THEFT, "test", null,
                Map.of(), true, CrimeCaseService.ResolutionGate.ALLOW_ALL);
        assertFalse(decision(data, 101L).mayChallenge());
    }

    @Test void jurisdictionIncludesDimensionAndNullIsNotGlobal() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        report(data, crime, new CrimeCommunityKey(new ResourceLocation("minecraft", "the_nether"), 1), 1F);
        report(data, crime, null, 1F);
        assertFalse(decision(data, 10L).mayChallenge());
        assertFalse(JusticeService.evaluate(data, OFFENDER, null, 10L, EVIDENCE, false, false).mayChallenge());
        assertTrue(JusticeService.evaluate(data, OFFENDER, LOCAL, 10L,
                new JusticeService.Settings(true, true, .75), false, false).mayChallenge());
    }

    @Test void explicitAndLegacyAuthorityRemainNamedPolicies() {
        var crime = record(1, 5, 20);
        var data = ledger(crime.withContext("detection", "command"));
        assertEquals(Set.of(LegalDecision.Basis.EXPLICIT_CASE), decision(data, 10L).basis());
        data.replaceRecord(crime);
        assertEquals(Set.of(LegalDecision.Basis.LEGACY_CASE), JusticeService.evaluate(data, OFFENDER,
                LOCAL, 10L, new JusticeService.Settings(false, false, .75), false, false).basis());
    }

    @Test void escapeAndActiveCaptivityCannotBePaidAwayWithUnrelatedCases() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        report(data, crime, LOCAL, 1F);
        for (boolean escaped : List.of(true, false)) {
            var decision = JusticeService.evaluate(data, OFFENDER, LOCAL, 10L, EVIDENCE, escaped, !escaped);
            assertTrue(decision.mayChallenge());
            assertTrue(decision.requiresCustody());
            assertEquals(SettlementQuote.RejectReason.MANDATORY_CUSTODY,
                    DispositionService.offer(data, decision, 5L, Band.GREY, 10L, FINES).quote().reject().orElseThrow());
        }
    }

    @Test void exactEmptySelectionHasNoHeatOnlyFallback() {
        var data = ledger(record(1, 5, 20));
        var quote = SettlementPolicy.quoteExact(data, OFFENDER, 20L, Band.GREY, List.of(), 10L, FINES);
        assertEquals(SettlementQuote.RejectReason.NOTHING_OWED, quote.reject().orElseThrow());
        assertEquals(0L, quote.heatCleared());
    }

    @Test void missingForeignAndDuplicateSelectionsRefuseTheEntirePayment() {
        var own = record(1, 5, 20);
        var other = new CrimeRecord(UUID.randomUUID(), UUID.randomUUID(), null, THEFT,
                OptionalInt.empty(), true, 1L, 5L, -5L, 20L, 0L, Resolution.UNRESOLVED);
        var data = ledger(own, other);
        for (List<UUID> ids : List.of(List.of(own.id(), UUID.randomUUID()),
                List.of(own.id(), other.id()), List.of(own.id(), own.id()))) {
            var quote = SettlementPolicy.quote(data, OFFENDER, 10L, Band.GREY, ids, true, 10L, FINES);
            assertFalse(quote.ok());
            assertFalse(FineService.pay(data, 10L, quote, amount -> fail("Must not debit"),
                    CrimeCaseService.ResolutionGate.ALLOW_ALL).paid());
        }
    }

    @Test void settlingEveryCaseInASelectionDoesNotClearUnrelatedHeat() {
        var crime = record(1, 5, 20);
        var quote = SettlementPolicy.quote(ledger(crime), OFFENDER, 30L, Band.GREY,
                List.of(crime.id()), true, 10L, FINES);
        assertEquals(5L, quote.heatCleared());
        assertEquals(20L, quote.amount());
    }

    @Test void newLocalChargeRequiresConsentEvenIfTotalPriceDoesNotChange() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        report(data, crime, LOCAL, 1F);
        var displayed = offer(data, 10L, 10L);
        var next = record(2, 5, 20);
        data.addRecord(next);
        report(data, next, LOCAL, 1F);
        assertFalse(DispositionService.current(displayed, offer(data, 10L, 11L), 11L));
    }

    @Test void penaltyFlagOrSentenceChangeInvalidatesQuoteWithoutResolutionRevision() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        report(data, crime, LOCAL, 1F);
        var displayed = offer(data, 10L, 10L);
        for (var changed : List.of(crime.withPenalty(40, 0), crime.withSentence(UUID.randomUUID()),
                crime.withContext(CrimeContext.FLAGS, "mandatory_custody"))) {
            assertEquals(crime.resolutionRevision(), changed.resolutionRevision());
            data.replaceRecord(changed);
            assertFalse(DispositionService.current(displayed, offer(data, 10L, 11L), 11L));
        }
    }

    @Test void newPrivateOrRemoteCaseDoesNotChangeTheLocalOfferWhenHeatIsUnchanged() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        report(data, crime, LOCAL, 1F);
        var displayed = offer(data, 10L, 10L);
        var remote = record(2, 5, 20);
        data.addRecord(remote);
        report(data, remote, REMOTE, 1F);
        data.addRecord(record(3, 5, 20));
        assertTrue(DispositionService.current(displayed, offer(data, 10L, 11L), 11L));
    }

    @Test void heatBandPolicyAndExpiryAreRevalidated() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        report(data, crime, LOCAL, 1F);
        var displayed = offer(data, 10L, 10L);
        assertFalse(DispositionService.current(displayed, offer(data, 11L, 11L), 11L));
        assertFalse(DispositionService.current(displayed, DispositionService.offer(data, decision(data, 11L),
                10L, Band.BLUE, 11L, FINES), 11L));
        assertFalse(DispositionService.current(displayed, DispositionService.offer(data, decision(data, 11L),
                10L, Band.GREY, 11L, new SettlementPolicy.Settings(false, 10, 2, 100, .5, true, 8)), 11L));
        assertFalse(DispositionService.current(displayed, displayed, 10L + SettlementQuote.VALIDITY_TICKS + 1L));
        assertFalse(DispositionService.current(displayed, DispositionService.offer(data, decision(data, 11L),
                10L, Band.GREY, 11L, FINES, "new:currency"), 11L));
    }

    @Test void refreshPreservesDeadlineAndRejectsOldResponses() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        report(data, crime, LOCAL, 1F);
        var offer = offer(data, 10L, 10L);
        var challenge = new GuardChallenge(UUID.randomUUID(), UUID.randomUUID(), OFFENDER, LOCAL,
                1, 20, true, 10, 400, 0, offer);
        var updated = challenge.refresh(offer(data, 11L, 20L));
        assertEquals(challenge.encounterId(), updated.encounterId());
        assertEquals(challenge.openedAt(), updated.openedAt());
        assertEquals(challenge.expiresAt(), updated.expiresAt());
        assertFalse(updated.accepts(challenge.encounterId(), challenge.revision()));
        assertTrue(updated.accepts(challenge.encounterId(), updated.revision()));
        assertFalse(updated.accepts(UUID.randomUUID(), updated.revision()));
        assertTrue(updated.acceptsSelection(List.of()));
        assertTrue(updated.acceptsSelection(List.of(crime.id())));
        assertFalse(updated.acceptsSelection(List.of(UUID.randomUUID())));
        assertFalse(updated.acceptsSelection(List.of(crime.id(), UUID.randomUUID())));
    }

    @Test void assessmentSurvivesWorldRoundTripWithoutInventingPrivateReports() {
        var crime = record(1, 5, 20);
        var data = ledger(crime, record(2, 5, 20));
        report(data, crime, LOCAL, 1F);
        var loaded = CrimeWorldData.load(data.save(new CompoundTag()));
        assertEquals(decision(data, 10L), decision(loaded, 10L));
    }

    @Test void futureDataCannotAuthorizeOrDebit() {
        var crime = record(1, 5, 20);
        var original = ledger(crime);
        report(original, crime, LOCAL, 1F);
        var quote = offer(original, 10L, 10L).quote();
        var tag = original.save(new CompoundTag());
        tag.putInt("schema", Integer.MAX_VALUE);
        var future = CrimeWorldData.load(tag);
        assertFalse(decision(future, 10L).mayChallenge());
        assertFalse(FineService.pay(future, 10L, quote, amount -> fail("Must not debit"),
                CrimeCaseService.ResolutionGate.ALLOW_ALL).paid());
    }

    @Test void aPreflightRunsOnceAndAllCasesCommitBeforeAnyNotification() {
        var first = record(1, 5, 20);
        var second = record(2, 5, 20);
        var data = ledger(first, second);
        var quote = SettlementPolicy.quoteExact(data, OFFENDER, 10L, Band.GREY,
                List.of(first.id(), second.id()), 10L, FINES);
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        var payment = FineService.pay(data, 10L, quote, amount -> true,
                (record, resolution) -> gates.incrementAndGet() <= 2, UUID.randomUUID(), resolved -> {
                    assertTrue(data.actionableFor(OFFENDER).isEmpty());
                    notifications.incrementAndGet();
                });
        assertTrue(payment.paid());
        assertEquals(2, gates.get());
        assertEquals(2, notifications.get());
    }

    @Test void callbackPenaltyMutationRefusesBeforeDebit() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        var quote = SettlementPolicy.quoteExact(data, OFFENDER, 10L, Band.GREY, List.of(crime.id()), 10L, FINES);
        assertFalse(FineService.pay(data, 10L, quote, amount -> fail("Must not debit"), (record, resolution) -> {
            data.replaceRecord(record.withPenalty(99, 0));
            return true;
        }).paid());
    }

    @Test void replayAndInsufficientFundsSettleNothingExtra() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        var quote = SettlementPolicy.quoteExact(data, OFFENDER, 10L, Band.GREY, List.of(crime.id()), 10L, FINES);
        assertFalse(FineService.pay(data, 10L, quote, amount -> false, CrimeCaseService.ResolutionGate.ALLOW_ALL).paid());
        assertTrue(data.recordById(crime.id()).orElseThrow().actionable());
        assertTrue(FineService.pay(data, 10L, quote, amount -> true, CrimeCaseService.ResolutionGate.ALLOW_ALL).paid());
        assertFalse(FineService.pay(data, 11L, quote, amount -> fail("Replay must not debit"),
                CrimeCaseService.ResolutionGate.ALLOW_ALL).paid());
    }

    @Test void casesAlreadyAssessedToASentenceCannotBeFinedThroughEitherSelectionMode() {
        var crime = record(1, 5, 20).withSentence(UUID.randomUUID());
        var data = ledger(crime);
        for (List<UUID> ids : List.of(List.<UUID>of(), List.of(crime.id()))) {
            var quote = SettlementPolicy.quote(data, OFFENDER, 10L, Band.GREY, ids, true, 10L, FINES);
            assertEquals(SettlementQuote.RejectReason.MANDATORY_CUSTODY, quote.reject().orElseThrow());
        }
    }

    @Test void decayedHeatDoesNotBypassTheOutlawPaymentPolicy() {
        var crime = record(1, 5, 20);
        var data = ledger(crime);
        var settings = new SettlementPolicy.Settings(true, 10, 2, 100, .5, false, 8);
        for (List<UUID> ids : List.of(List.<UUID>of(), List.of(crime.id()))) {
            var quote = SettlementPolicy.quote(data, OFFENDER, 0L, Band.RED, ids, true, 10L, settings);
            assertEquals(SettlementQuote.RejectReason.BARRED, quote.reject().orElseThrow());
        }
    }

    @Test void aBrokenNotificationDoesNotUndoPaymentOrSuppressTheNextCase() {
        var first = record(1, 5, 20);
        var second = record(2, 5, 20);
        var data = ledger(first, second);
        var quote = SettlementPolicy.quoteExact(data, OFFENDER, 10L, Band.GREY,
                List.of(first.id(), second.id()), 10L, FINES);
        AtomicInteger notified = new AtomicInteger();
        var paid = FineService.pay(data, 10L, quote, amount -> true, CrimeCaseService.ResolutionGate.ALLOW_ALL,
                UUID.randomUUID(), resolved -> {
                    if (notified.incrementAndGet() == 1) throw new IllegalStateException("Test listener failure");
                });
        assertTrue(paid.paid());
        assertEquals(0L, paid.newHeat());
        assertTrue(data.actionableFor(OFFENDER).isEmpty());
        assertEquals(2, notified.get());
    }
}
