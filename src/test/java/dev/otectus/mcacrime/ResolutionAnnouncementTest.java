package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.ledger.SentenceResolutionService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A settled case says so.
 *
 * <p>0.6.0 gave eight services a ledger-only overload so they could be tested without a server, and
 * two of them — the fine and the sentence — were switched over to it internally. Those overloads
 * cannot post an event or queue a companion-mod incident, so both quietly stopped: the cases closed,
 * the money moved, the term was served, and nothing outside the ledger was ever told. Since
 * {@code README} promises that paying a fine or serving a sentence "reads publicly as making good",
 * the break was in a documented public surface rather than an internal detail.
 *
 * <p>The bus and the outbox need a running server and so cannot be asserted on directly. What can be
 * asserted is the seam they hang from: {@link CrimeCaseService.ResolutionSink} is bound to both in
 * play and to a list here, exactly as {@link CrimeCaseService.ResolutionGate} is bound to a policy in
 * play and to a lambda in a test. If a caller ever routes past the sink again, these fail.
 */
class ResolutionAnnouncementTest {

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final ResourceLocation THEFT = ResourceLocation.fromNamespaceAndPath("mcacrime", "theft");
    /** Fine base 10, 2 per Heat, jailable at 100, no Blue discount, outlaws may pay, 8 cases a payment. */
    private static final SettlementPolicy.Settings SETTINGS =
            new SettlementPolicy.Settings(true, 10, 2, 100, 1.0D, true, 8);

    /** The sink under test: every announcement, in the order it was made. */
    private static final class RecordingSink implements CrimeCaseService.ResolutionSink {

        private final List<CrimeCaseService.Resolved> announced = new ArrayList<>();

        @Override
        public void announce(CrimeCaseService.Resolved resolved) {
            announced.add(resolved);
        }

        List<UUID> caseIds() {
            return announced.stream().map(resolved -> resolved.after().id()).collect(Collectors.toList());
        }
    }

    private static CrimeRecord open(long committed, long heat, long fine) {
        return new CrimeRecord(UUID.randomUUID(), OFFENDER, null, THEFT, OptionalInt.empty(), null,
                true, Set.of(), committed, heat, -5L, fine, 0L, Resolution.UNRESOLVED, 0L,
                List.of(), null, Map.of());
    }

    private static CrimeWorldData ledgerOf(CrimeRecord... records) {
        CrimeWorldData data = new CrimeWorldData();
        for (CrimeRecord record : records) {
            data.addRecord(record);
        }
        return data;
    }

    // ------------------------------------------------------------------ the fine

    @Test
    void payingAFineAnnouncesEverySettledCaseExactlyOnce() {
        CrimeRecord first = open(10L, 6L, 25L);
        CrimeRecord second = open(20L, 4L, 15L);
        CrimeWorldData data = ledgerOf(first, second);

        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 10L, Band.GREY, List.of(), true,
                0L, SETTINGS);
        RecordingSink sink = new RecordingSink();
        FineService.Payment payment = FineService.pay(data, 0L, quote, amount -> true,
                CrimeCaseService.ResolutionGate.ALLOW_ALL, UUID.randomUUID(), sink);

        assertTrue(payment.paid());
        assertEquals(2, payment.settledCaseIds().size());
        assertEquals(payment.settledCaseIds(), sink.caseIds(),
                "One announcement per settled case, in settlement order: this is the outbox entry and "
                        + "the resolved event a companion mod reads restitution from");
    }

    @Test
    void aFineAnnouncesTheTransitionRatherThanJustTheDestination() {
        CrimeRecord only = open(10L, 6L, 25L);
        CrimeWorldData data = ledgerOf(only);

        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 6L, Band.GREY, List.of(), true,
                0L, SETTINGS);
        RecordingSink sink = new RecordingSink();
        FineService.pay(data, 0L, quote, amount -> true, CrimeCaseService.ResolutionGate.ALLOW_ALL,
                UUID.randomUUID(), sink);

        assertEquals(1, sink.announced.size());
        CrimeCaseService.Resolved resolved = sink.announced.get(0);
        // "Unresolved to fined" is restitution and "escaped to fined" is someone finally settling up.
        // A listener handed only the new state cannot tell those apart, so both halves have to travel.
        assertEquals(Resolution.UNRESOLVED, resolved.before().resolution());
        assertEquals(Resolution.FINED, resolved.after().resolution());
        assertEquals(Resolution.FINED, resolved.entry().resolution());
        assertEquals(OFFENDER, resolved.after().offenderId(),
                "The live sink looks the offender up by this id to post the event against them");
    }

    @Test
    void aRefusedFineAnnouncesNothing() {
        CrimeWorldData data = ledgerOf(open(10L, 6L, 25L));
        SettlementQuote quote = SettlementPolicy.quote(data, OFFENDER, 6L, Band.GREY, List.of(), true,
                0L, SETTINGS);

        RecordingSink sink = new RecordingSink();
        FineService.Payment payment = FineService.pay(data, 0L, quote, amount -> false,
                CrimeCaseService.ResolutionGate.ALLOW_ALL, UUID.randomUUID(), sink);

        assertFalse(payment.paid());
        assertTrue(sink.announced.isEmpty(),
                "A payment nobody could afford settled nothing, so it has nothing to announce");
    }

    // ------------------------------------------------------------------ the sentence

    @Test
    void aServedSentenceAnnouncesEverySettledCaseExactlyOnce() {
        CrimeRecord first = open(100L, 6L, 25L);
        CrimeRecord second = open(200L, 4L, 15L);
        CrimeWorldData data = ledgerOf(first, second);

        UUID sentence = UUID.randomUUID();
        assertEquals(2, data.bindSentence(OFFENDER, sentence, 300L).size());

        RecordingSink sink = new RecordingSink();
        List<UUID> served = SentenceResolutionService.markServed(data, 500L, OFFENDER, sentence,
                CrimeCaseService.ResolutionGate.ALLOW_ALL, sink);

        assertEquals(2, served.size());
        assertEquals(served, sink.caseIds(),
                "Doing the time has to read as making good just as paying does; this is the only "
                        + "signal that says so");
        assertTrue(sink.announced.stream()
                        .allMatch(resolved -> resolved.after().resolution() == Resolution.SERVED),
                "A sentence announces what it wrote");
    }

    @Test
    void aReplayedReleaseAnnouncesNothingTheSecondTime() {
        CrimeWorldData data = ledgerOf(open(100L, 6L, 25L));
        UUID sentence = UUID.randomUUID();
        data.bindSentence(OFFENDER, sentence, 300L);

        RecordingSink first = new RecordingSink();
        SentenceResolutionService.markServed(data, 500L, OFFENDER, sentence,
                CrimeCaseService.ResolutionGate.ALLOW_ALL, first);
        assertEquals(1, first.announced.size());

        RecordingSink again = new RecordingSink();
        SentenceResolutionService.markServed(data, 600L, OFFENDER, sentence,
                CrimeCaseService.ResolutionGate.ALLOW_ALL, again);

        assertTrue(again.announced.isEmpty(),
                "A retried release must not make the player look like they served the term twice");
    }
}
