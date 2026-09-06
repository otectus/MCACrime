package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.ledger.SentenceResolutionService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sentence settles what it was for, and nothing else (spec §9.2).
 *
 * <p>Releasing a prisoner used to close every case actionable against them at that moment. The
 * difference only shows up in one situation, and it is not a rare one: a prisoner who breaks out,
 * commits something on the run, and is recaptured had that new crime settled by the term they were
 * already serving. Membership is the fix — a case is charged under a sentence when the sentence is
 * handed down, and the release closes exactly that set.
 */
class SentenceCaseBindingTest {

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final UUID THIEF = UUID.randomUUID();
    private static final ResourceLocation THEFT = new ResourceLocation("mcacrime", "theft");

    private static CrimeRecord open(UUID offender, long committed) {
        return new CrimeRecord(UUID.randomUUID(), offender, null, THEFT, OptionalInt.empty(), null,
                true, Set.of(), committed, 10L, -5L, 20L, 0L, Resolution.UNRESOLVED, 0L,
                List.of(), null, Map.of());
    }

    private static Resolution resolutionOf(CrimeWorldData data, UUID id) {
        return data.recordById(id).orElseThrow().resolution();
    }

    // ------------------------------------------------------------------ T06

    @Test
    void aServedSentenceSettlesItsOwnCasesAndLeavesALaterOneOpen() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord first = open(OFFENDER, 100L);
        CrimeRecord second = open(OFFENDER, 200L);
        data.addRecord(first);
        data.addRecord(second);

        UUID sentence = UUID.randomUUID();
        assertEquals(2, data.bindSentence(OFFENDER, sentence, 300L).size());

        // Committed after the cell door closed: nobody sentenced this, so nothing may settle it.
        CrimeRecord onTheRun = open(OFFENDER, 400L);
        data.addRecord(onTheRun);

        List<UUID> served = SentenceResolutionService.markServed(data, 500L, OFFENDER, sentence,
                CrimeCaseService.ResolutionGate.ALLOW_ALL);

        assertEquals(2, served.size());
        assertEquals(Resolution.SERVED, resolutionOf(data, first.id()));
        assertEquals(Resolution.SERVED, resolutionOf(data, second.id()));
        assertEquals(Resolution.UNRESOLVED, resolutionOf(data, onTheRun.id()));
        assertEquals(1, data.actionableFor(OFFENDER).size(),
                "The escapee's new crime survives the sentence they were already serving");
    }

    @Test
    void aSentenceThatBindsNothingSettlesNothingRatherThanEverything() {
        // The legacy shape: an in-flight 0.5.1 sentence, no membership, and a ledger full of open
        // cases. Closing them all to cover the missing field would be an amnesty issued by an absence.
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord charge = open(OFFENDER, 100L);
        data.addRecord(charge);

        List<UUID> served = SentenceResolutionService.markServed(data, 500L, OFFENDER, UUID.randomUUID(),
                CrimeCaseService.ResolutionGate.ALLOW_ALL);

        assertTrue(served.isEmpty());
        assertEquals(Resolution.UNRESOLVED, resolutionOf(data, charge.id()));
    }

    @Test
    void aSecondSentenceCannotStealTheFirstSentencesCharges() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord charge = open(OFFENDER, 100L);
        data.addRecord(charge);

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        data.bindSentence(OFFENDER, first, 200L);

        assertTrue(data.bindSentence(OFFENDER, second, 300L).isEmpty());
        assertEquals(1, data.casesForSentence(OFFENDER, first).size());
        assertTrue(data.casesForSentence(OFFENDER, second).isEmpty());
    }

    @Test
    void breakingOutMarksOnlyTheCasesTheSentenceCovered() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord charge = open(OFFENDER, 100L);
        data.addRecord(charge);
        UUID sentence = UUID.randomUUID();
        data.bindSentence(OFFENDER, sentence, 150L);
        CrimeRecord unrelated = open(OFFENDER, 400L);
        data.addRecord(unrelated);

        SentenceResolutionService.markEscaped(data, 500L, OFFENDER, sentence,
                CrimeCaseService.ResolutionGate.ALLOW_ALL);

        assertEquals(Resolution.ESCAPED, resolutionOf(data, charge.id()));
        assertEquals(Resolution.UNRESOLVED, resolutionOf(data, unrelated.id()));
        assertEquals(2, data.actionableFor(OFFENDER).size(), "Escaping is not forgiveness");
    }

    // ------------------------------------------------------------------ T07

    @Test
    void anNpcReleaseSettlesTheThiefsOwnBoundCases() {
        // The NPC path mints one id for the cell and the sentence and binds on arrival; the release
        // reads it back off the cell. Everything either side of that is the same service.
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord thiefCase = open(THIEF, 100L);
        CrimeRecord somebodyElse = open(OFFENDER, 100L);
        data.addRecord(thiefCase);
        data.addRecord(somebodyElse);

        UUID sentence = UUID.randomUUID();
        data.bindSentence(THIEF, sentence, 200L);

        List<UUID> served = SentenceResolutionService.markServed(data, 900L, THIEF, sentence,
                CrimeCaseService.ResolutionGate.ALLOW_ALL);

        assertEquals(List.of(thiefCase.id()), served);
        assertEquals(Resolution.SERVED, resolutionOf(data, thiefCase.id()));
        assertEquals(Resolution.UNRESOLVED, resolutionOf(data, somebodyElse.id()),
                "One villager's sentence is not an amnesty for the village");
    }

    // ------------------------------------------------------------------ legacy inference

    @Test
    void aLegacySentenceAdoptsTheStandingChargesAndSaysThatIsWhatItDid() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord charge = open(OFFENDER, 100L);
        data.addRecord(charge);

        UUID legacy = UUID.randomUUID();
        assertTrue(data.casesForSentence(OFFENDER, legacy).isEmpty(), "Nothing in a 0.5.1 save binds it");

        List<UUID> bound = data.bindLegacySentence(OFFENDER, legacy, 777L);

        assertEquals(List.of(charge.id()), bound);
        CrimeRecord after = data.recordById(charge.id()).orElseThrow();
        assertEquals(legacy, after.sentenceId());
        assertEquals("777", after.context().get(CrimeContext.LEGACY_SENTENCE_INFERRED),
                "The ledger has to admit this binding was assumed, not recorded");
        assertEquals(1, SentenceResolutionService.markServed(data, 800L, OFFENDER, legacy,
                CrimeCaseService.ResolutionGate.ALLOW_ALL).size());
    }

    @Test
    void theInferenceLeavesCasesThatAlreadyBelongToASentenceAlone() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord charge = open(OFFENDER, 100L);
        data.addRecord(charge);
        UUID real = UUID.randomUUID();
        data.bindSentence(OFFENDER, real, 200L);

        assertTrue(data.bindLegacySentence(OFFENDER, UUID.randomUUID(), 777L).isEmpty());
        assertEquals(real, data.recordById(charge.id()).orElseThrow().sentenceId());
    }

    // ------------------------------------------------------------------ persistence

    @Test
    void membershipSurvivesASaveAndReload() {
        CrimeRecord charged = open(OFFENDER, 100L).withSentence(UUID.randomUUID());

        CrimeRecord loaded = CrimeRecord.load(charged.save());

        assertEquals(charged, loaded);
        assertEquals(charged.sentenceId(), loaded.sentenceId());
        assertEquals(charged.sentenceId(), loaded.sentence().orElseThrow());
    }

    @Test
    void aCaseWrittenBeforeMembershipExistedLoadsUnbound() {
        // Every record in a 0.5.1 world, and every record since that no sentence has claimed.
        CompoundTag legacy = open(OFFENDER, 100L).save();
        assertFalse(legacy.contains("sentenceId"));

        CrimeRecord loaded = CrimeRecord.load(legacy);

        assertNull(loaded.sentenceId());
        assertTrue(loaded.sentence().isEmpty());
        assertEquals(100L, loaded.timeCommitted());
    }

    @Test
    void bindingIsACopyRatherThanAnEditOfTheRecordInHand() {
        CrimeRecord unbound = open(OFFENDER, 100L);
        UUID sentence = UUID.randomUUID();

        CrimeRecord charged = unbound.withSentence(sentence);

        assertNull(unbound.sentenceId(), "CrimeRecord stays immutable; the ledger swaps the copy in");
        assertEquals(sentence, charged.sentenceId());
        assertEquals(unbound.id(), charged.id());
        assertEquals(unbound.resolutionRevision(), charged.resolutionRevision(),
                "Charging a case under a sentence is not a disposition change");
    }
}
