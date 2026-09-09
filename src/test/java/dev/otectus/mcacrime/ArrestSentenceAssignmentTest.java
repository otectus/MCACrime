package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.*;
import dev.otectus.mcacrime.enforcement.NpcCustodyService;
import dev.otectus.mcacrime.enforcement.ResponderAssignments;
import dev.otectus.mcacrime.ledger.*;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArrestSentenceAssignmentTest {
    private static final UUID OFFENDER = UUID.randomUUID();
    private static final UUID GUARD = UUID.randomUUID();
    private static final ResourceLocation DIM = ResourceLocation.tryParse("minecraft:overworld");
    private static final ResourceLocation THEFT = ResourceLocation.tryParse("mcacrime:theft");

    private CrimeRecord crime(UUID offender) {
        return new CrimeRecord(UUID.randomUUID(), offender, null, THEFT, OptionalInt.empty(),
                true, 10L, 5L, -5L, 5L, 0L, Resolution.UNRESOLVED);
    }

    private CrimeWorldData held(boolean player) {
        CrimeWorldData data = new CrimeWorldData();
        assertTrue(CustodyService.captureLawful(data, OFFENDER, player, CustodyOwner.guard(GUARD),
                RestraintType.CUFFS, 100L, BlockPos.ZERO, DIM).ok());
        return data;
    }

    @Test void onlyAssessedCasesAreBoundAndArrivalCannotAddAnother() {
        CrimeWorldData data = held(true);
        CrimeRecord assessed = crime(OFFENDER);
        CrimeRecord privateCase = crime(OFFENDER);
        CrimeRecord anotherPlayer = crime(UUID.randomUUID());
        data.addRecord(assessed);
        data.addRecord(privateCase);
        data.addRecord(anotherPlayer);
        UUID sentence = UUID.randomUUID();
        assertTrue(SentenceAssignmentService.assign(data, OFFENDER, sentence,
                List.of(assessed.id(), anotherPlayer.id()), 100L));
        CrimeRecord duringEscort = crime(OFFENDER);
        data.addRecord(duringEscort);
        assertTrue(SentenceAssignmentService.assign(data, OFFENDER, sentence,
                List.of(duringEscort.id()), 200L));
        assertEquals(List.of(assessed.id()), data.casesForSentence(OFFENDER, sentence).stream()
                .map(CrimeRecord::id).toList());
        assertNull(data.recordById(privateCase.id()).orElseThrow().sentenceId());
        assertNull(data.recordById(duringEscort.id()).orElseThrow().sentenceId());
        assertNull(data.recordById(anotherPlayer.id()).orElseThrow().sentenceId());
    }

    @Test void anEmptyAssessmentStaysEmptyAcrossWorldReload() {
        CrimeWorldData data = held(false);
        UUID sentence = UUID.randomUUID();
        assertTrue(SentenceAssignmentService.assign(data, OFFENDER, sentence, List.of(), 100L));
        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY), net.minecraft.core.RegistryAccess.EMPTY);
        CrimeRecord later = crime(OFFENDER);
        loaded.addRecord(later);
        assertEquals(sentence, loaded.getCustody(OFFENDER).getSentenceId());
        assertTrue(SentenceAssignmentService.assign(loaded, OFFENDER, sentence, List.of(later.id()), 200L));
        assertTrue(loaded.casesForSentence(OFFENDER, sentence).isEmpty());
    }

    @Test void failedCaptureAndUnlawfulCustodyCannotAssignASentence() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord charge = crime(OFFENDER);
        data.addRecord(charge);
        assertFalse(SentenceAssignmentService.assign(data, OFFENDER, UUID.randomUUID(), List.of(charge.id()), 1L));
        data.putCustody(new CustodyRecord(OFFENDER, false, false, CustodyOwner.kidnapper(GUARD),
                RestraintType.ROPE, 0L, BlockPos.ZERO, DIM));
        assertFalse(SentenceAssignmentService.assign(data, OFFENDER, UUID.randomUUID(), List.of(charge.id()), 1L));
        assertNull(data.recordById(charge.id()).orElseThrow().sentenceId());
    }

    @Test void oneCustodyRecordCannotBeAssignedTwoSentences() {
        CrimeWorldData data = held(false);
        UUID first = UUID.randomUUID();
        assertTrue(SentenceAssignmentService.assign(data, OFFENDER, first, List.of(), 100L));
        assertFalse(SentenceAssignmentService.assign(data, OFFENDER, UUID.randomUUID(), List.of(), 101L));
        assertEquals(first, data.getCustody(OFFENDER).getSentenceId());
    }

    @Test void intakeRecoversTheDurableIdentityAndRefusesAConflictingOne() {
        UUID sentence = UUID.randomUUID();
        assertEquals(sentence, SentenceAssignmentService.intakeId(null, sentence).orElseThrow());
        assertEquals(sentence, SentenceAssignmentService.intakeId(sentence, sentence).orElseThrow());
        assertEquals(sentence, SentenceAssignmentService.intakeId(sentence, null).orElseThrow());
        assertTrue(SentenceAssignmentService.intakeId(UUID.randomUUID(), sentence).isEmpty());
        assertTrue(SentenceAssignmentService.intakeId(null, null).isPresent());
    }

    @Test void cellLessNpcSentenceResolvesAfterReload() {
        CrimeWorldData data = held(false);
        CrimeRecord charge = crime(OFFENDER);
        data.addRecord(charge);
        UUID sentence = UUID.randomUUID();
        SentenceAssignmentService.assign(data, OFFENDER, sentence, List.of(charge.id()), 100L);
        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY), net.minecraft.core.RegistryAccess.EMPTY);
        var custody = loaded.getCustody(OFFENDER);
        custody.setOwner(CustodyOwner.jail(2, BlockPos.ZERO, DIM));
        assertEquals(sentence, custody.copy().getSentenceId());
        assertEquals(List.of(charge.id()), SentenceResolutionService.markServed(loaded, 500L,
                OFFENDER, custody.getSentenceId(), CrimeCaseService.ResolutionGate.ALLOW_ALL));
        assertTrue(SentenceResolutionService.markServed(loaded, 501L,
                OFFENDER, sentence, CrimeCaseService.ResolutionGate.ALLOW_ALL).isEmpty());
    }

    @Test void failedIntakeUnbindsItsCasesWithoutResolvingOrStealingOthers() {
        CrimeWorldData data = held(false);
        CrimeRecord charge = crime(OFFENDER);
        UUID otherSentence = UUID.randomUUID();
        CrimeRecord other = crime(OFFENDER).withSentence(otherSentence);
        data.addRecord(charge);
        data.addRecord(other);
        UUID sentence = UUID.randomUUID();
        SentenceAssignmentService.assign(data, OFFENDER, sentence, List.of(charge.id(), other.id()), 100L);
        SentenceAssignmentService.cancel(data, OFFENDER, sentence);
        assertNull(data.recordById(charge.id()).orElseThrow().sentenceId());
        assertEquals(Resolution.UNRESOLVED, data.recordById(charge.id()).orElseThrow().resolution());
        assertEquals(otherSentence, data.recordById(other.id()).orElseThrow().sentenceId());
        assertEquals(List.of(charge.id()), data.bindSentence(OFFENDER, UUID.randomUUID(), 200L, List.of(charge.id())));
    }

    @Test void cancellationPreservesTerminalSentenceHistory() {
        CrimeWorldData data = held(false);
        CrimeRecord charge = crime(OFFENDER);
        data.addRecord(charge);
        UUID sentence = UUID.randomUUID();
        SentenceAssignmentService.assign(data, OFFENDER, sentence, List.of(charge.id()), 100L);
        SentenceResolutionService.markServed(data, 200L, OFFENDER, sentence, CrimeCaseService.ResolutionGate.ALLOW_ALL);
        SentenceAssignmentService.cancel(data, OFFENDER, sentence);
        assertEquals(sentence, data.recordById(charge.id()).orElseThrow().sentenceId());
        assertEquals(Resolution.SERVED, data.recordById(charge.id()).orElseThrow().resolution());
    }

    @Test void futureSchemaRefusesAssignment() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", CrimeDataMigrations.CURRENT_SCHEMA + 1);
        CrimeWorldData data = CrimeWorldData.load(tag, net.minecraft.core.RegistryAccess.EMPTY);
        assertFalse(SentenceAssignmentService.assign(data, OFFENDER, UUID.randomUUID(), List.of(), 100L));
    }

    @Test void legacyCustodyLoadsWithoutPretendingItWasAssessed() {
        CustodyRecord legacy = CustodyRecord.load(held(false).getCustody(OFFENDER).save());
        assertNull(legacy.getSentenceId());
    }

    @Test void escortDeadlineSurvivesSaveAndGuardReplacement() {
        CustodyRecord record = held(false).getCustody(OFFENDER);
        record.setRealTicksHeld(600L);
        CustodyRecord loaded = CustodyRecord.load(record.save());
        loaded.setOwner(CustodyOwner.guard(UUID.randomUUID()));
        assertTrue(NpcCustodyService.escortOverdue(loaded.getRealTicksHeld(), 600L));
        assertFalse(NpcCustodyService.escortOverdue(599L, 600L));
        assertTrue(NpcCustodyService.escortOverdue(0L, 0L));
    }

    @Test void aGuardAlreadyEscortingSomeoneElseCannotBeBorrowed() {
        CrimeWorldData data = held(true);
        assertFalse(ResponderAssignments.isEscorting(data, GUARD, OFFENDER));
        assertTrue(ResponderAssignments.isEscorting(data, GUARD, UUID.randomUUID()));
        data.getCustody(OFFENDER).setOwner(CustodyOwner.jail(1, BlockPos.ZERO, DIM));
        assertFalse(ResponderAssignments.isEscorting(data, GUARD, UUID.randomUUID()));
    }
}
