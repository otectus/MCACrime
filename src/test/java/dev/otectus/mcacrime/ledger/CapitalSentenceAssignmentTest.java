package dev.otectus.mcacrime.ledger;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Marking a sentence capital, once, and never again (0.7.5 §3.19, acceptance 21.6).
 *
 * <p>The kind is decided at the moment custody commits, from the cases that arrest assessed. Every
 * test here is one way that decision could otherwise be revisited — a replayed arrival, a second
 * arrest, a case filed after the cell door closed — and the answer in all of them is the same: the
 * sentence keeps the kind it was bound with.
 *
 * <p>Against a bare {@link CrimeWorldData} rather than a server, because the whole model is store
 * state: a table keyed by sentence, a mirror on the custody record, and NBT.
 */
class CapitalSentenceAssignmentTest {

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final UUID GUARD = UUID.randomUUID();
    private static final ResourceLocation DIM = ResourceLocation.tryParse("minecraft:overworld");

    private static CrimeRecord guardKilling() {
        return new CrimeRecord(UUID.randomUUID(), OFFENDER, null, CrimeIds.KILL_GUARD,
                OptionalInt.empty(), true, 10L, 60L, -80L, 0L, 1200L, Resolution.UNRESOLVED);
    }

    private static CrimeRecord theft() {
        return new CrimeRecord(UUID.randomUUID(), OFFENDER, null, CrimeIds.THEFT, OptionalInt.empty(),
                true, 10L, 12L, -8L, 20L, 0L, Resolution.UNRESOLVED);
    }

    private static CrimeWorldData held(boolean player) {
        CrimeWorldData data = new CrimeWorldData();
        assertTrue(CustodyService.captureLawful(data, OFFENDER, player, CustodyOwner.guard(GUARD),
                100L, BlockPos.ZERO, DIM).ok());
        return data;
    }

    private static UUID bind(CrimeWorldData data, CrimeRecord... cases) {
        UUID sentence = UUID.randomUUID();
        for (CrimeRecord record : cases) {
            data.addRecord(record);
        }
        assertTrue(SentenceAssignmentService.assign(data, OFFENDER, sentence,
                List.of(cases).stream().map(CrimeRecord::id).toList(), 100L));
        return sentence;
    }

    @Test
    void aGuardKillingBindsACapitalSentenceAndMirrorsItOntoTheCustody() {
        CrimeWorldData data = held(true);
        UUID sentence = bind(data, guardKilling());

        assertEquals(SentenceKind.CAPITAL,
                CapitalSentenceService.mark(data, OFFENDER, sentence, false));
        assertEquals(SentenceKind.CAPITAL, data.sentenceKind(sentence));
        assertTrue(data.getCustody(OFFENDER).isCondemned(),
                "the custody record mirrors the table it is serving under");
    }

    @Test
    void anOrdinarySentenceIsNotMarkedAndWritesNoRow() {
        CrimeWorldData data = held(true);
        UUID sentence = bind(data, theft());

        assertEquals(SentenceKind.CUSTODIAL, CapitalSentenceService.mark(data, OFFENDER, sentence, false));
        assertEquals(SentenceKind.CUSTODIAL, data.sentenceKind(sentence));
        assertFalse(data.getCustody(OFFENDER).isCondemned());
        assertTrue(data.sentenceKinds().isEmpty(),
                "a custodial sentence is the default and must cost the save file nothing");
    }

    @Test
    void assignmentIsIdempotent() {
        CrimeWorldData data = held(true);
        UUID sentence = bind(data, guardKilling());

        assertEquals(SentenceKind.CAPITAL, CapitalSentenceService.mark(data, OFFENDER, sentence, false));
        assertEquals(SentenceKind.CAPITAL, CapitalSentenceService.mark(data, OFFENDER, sentence, false),
                "a replayed arrival must neither duplicate nor re-decide the kind");
        assertEquals(1, data.sentenceKinds().size());
    }

    /** A guard killing filed after the cell door closed cannot upgrade the sentence being served. */
    @Test
    void aSentenceIsNeverUpgradedAfterBinding() {
        CrimeWorldData data = held(true);
        UUID sentence = bind(data, theft());
        assertEquals(SentenceKind.CUSTODIAL, CapitalSentenceService.mark(data, OFFENDER, sentence, false));

        CrimeRecord later = guardKilling();
        data.addRecord(later);
        SentenceAssignmentService.assign(data, OFFENDER, sentence, List.of(later.id()), 200L);

        assertEquals(SentenceKind.CUSTODIAL, CapitalSentenceService.mark(data, OFFENDER, sentence, false),
                "a case filed after the sentence was bound is not part of what was sentenced");
        assertFalse(data.getCustody(OFFENDER).isCondemned());
    }

    @Test
    void aSentenceNobodyIsBeingHeldUnderIsNotMarked() {
        CrimeWorldData data = held(true);
        bind(data, guardKilling());

        UUID other = UUID.randomUUID();
        assertEquals(SentenceKind.CUSTODIAL, CapitalSentenceService.mark(data, OFFENDER, other, false),
                "marking a sentence the custody is not serving would condemn somebody for nothing");
    }

    @Test
    void npcOffenderRequiresOptIn() {
        CrimeWorldData data = held(false);
        UUID sentence = bind(data, guardKilling());

        // The live-config overload defaults npcOffendersEligible to false with no config loaded.
        assertEquals(SentenceKind.CUSTODIAL, CapitalSentenceService.mark(data, OFFENDER, sentence, true));
        assertFalse(data.getCustody(OFFENDER).isCondemned());

        assertTrue(CapitalSentenceService.qualifies(data.casesForSentence(OFFENDER, sentence),
                true, true, true, true), "with the opt-in on, the same cases do qualify");
    }

    @Test
    void theKindSurvivesASaveAndLoad() {
        CrimeWorldData data = held(true);
        UUID sentence = bind(data, guardKilling());
        CapitalSentenceService.mark(data, OFFENDER, sentence, false);

        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        assertEquals(SentenceKind.CAPITAL, loaded.sentenceKind(sentence));
        assertTrue(loaded.getCustody(OFFENDER).isCondemned());
    }

    /** The additive-field rule: a world written before this release has no capital sentence in it. */
    @Test
    void legacySentenceLoadsAsCustodial() {
        CrimeWorldData data = held(true);
        UUID sentence = bind(data, theft());
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertFalse(saved.contains("sentenceKinds"), "nothing is written for an ordinary sentence");

        CrimeWorldData loaded = CrimeWorldData.load(saved, RegistryAccess.EMPTY);
        assertEquals(SentenceKind.CUSTODIAL, loaded.sentenceKind(sentence));
        assertFalse(loaded.getCustody(OFFENDER).isCondemned());
        assertEquals(SentenceKind.CUSTODIAL, SentenceKind.parseOr(null, SentenceKind.CUSTODIAL));
        assertEquals(SentenceKind.CUSTODIAL, SentenceKind.parseOr("", SentenceKind.CUSTODIAL));
    }

    @Test
    void aCommutedSentenceClearsItsRowRatherThanRecordingTheDefault() {
        CrimeWorldData data = held(true);
        UUID sentence = bind(data, guardKilling());
        CapitalSentenceService.mark(data, OFFENDER, sentence, false);

        assertTrue(data.setSentenceKind(sentence, SentenceKind.CUSTODIAL));
        assertTrue(data.sentenceKinds().isEmpty(),
                "clemency removes the fact rather than leaving a row that contradicts it");
        CustodyRecord custody = data.getCustody(OFFENDER);
        custody.setSentenceKind(SentenceKind.CUSTODIAL);
        assertFalse(custody.isCondemned());
    }
}
