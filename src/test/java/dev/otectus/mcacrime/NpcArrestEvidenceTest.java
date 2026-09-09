package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry;
import dev.otectus.mcacrime.enforcement.NpcArrestEvidence;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcArrestEvidenceTest {
    private static final UUID THIEF = UUID.randomUUID();
    private static final ResourceLocation MUGGING = ResourceLocation.tryParse("mcacrime:mugging");

    private CrimeRecord crime() {
        return new CrimeRecord(UUID.randomUUID(), THIEF, null, MUGGING, OptionalInt.empty(),
                true, 10L, 0L, 0L, 10L, 100L, Resolution.UNRESOLVED);
    }

    private ActiveIncidentRegistry.ActiveIncident incident(UUID id, ActiveIncidentRegistry.Phase phase) {
        return new ActiveIncidentRegistry.ActiveIncident(id, THIEF, null, null, 10L,
                EnumSet.of(CrimeFlag.NPC_OFFENDER), phase);
    }

    @Test void reportBasedArrestUsesTheCompletedMuggingWithoutAddingAnAttempt() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord charge = crime();
        data.addRecord(charge);
        CrimeReport report = new CrimeReport(UUID.randomUUID(), charge.id(), UUID.randomUUID(),
                UUID.randomUUID(), THIEF, MUGGING, null, 11L, 1000L, 1F, false);
        data.addReport(report);
        assertEquals(charge, NpcArrestEvidence.caseFor(data,
                incident(report.reportId(), ActiveIncidentRegistry.Phase.COMMITTED)).orElseThrow());
        assertEquals(charge, NpcArrestEvidence.caseFor(data,
                incident(charge.id(), ActiveIncidentRegistry.Phase.COMMITTED)).orElseThrow());
        assertEquals(1, data.ledgerSize());
    }

    @Test void aResolvedCaseStopsAuthorizingThePursuit() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord charge = crime();
        data.addRecord(charge);
        var basis = incident(charge.id(), ActiveIncidentRegistry.Phase.COMMITTED);
        assertTrue(NpcArrestEvidence.caseFor(data, basis).isPresent());
        data.replaceRecord(new CrimeRecord(charge.id(), THIEF, null, MUGGING, OptionalInt.empty(),
                true, 10L, 0L, 0L, 10L, 100L, Resolution.SERVED));
        assertTrue(NpcArrestEvidence.caseFor(data, basis).isEmpty());
    }

    @Test void missingEvidenceAndAnotherOffendersCaseAreRefused() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeRecord charge = crime();
        data.addRecord(charge);
        assertTrue(NpcArrestEvidence.caseFor(data, null).isEmpty());
        assertTrue(NpcArrestEvidence.caseFor(data,
                incident(UUID.randomUUID(), ActiveIncidentRegistry.Phase.COMMITTED)).isEmpty());
        var other = new ActiveIncidentRegistry.ActiveIncident(charge.id(), UUID.randomUUID(), null,
                null, 10L, EnumSet.noneOf(CrimeFlag.class), ActiveIncidentRegistry.Phase.COMMITTED);
        assertTrue(NpcArrestEvidence.caseFor(data, other).isEmpty());
    }

    @Test void onlyTheCurrentUncompletedThreatCanBeRecordedAsAnAttempt() {
        UUID id = UUID.randomUUID();
        var threat = incident(id, ActiveIncidentRegistry.Phase.THREAT);
        assertTrue(NpcArrestEvidence.isCurrentThreat(threat, threat, id));
        assertFalse(NpcArrestEvidence.isCurrentThreat(threat, null, id));
        assertFalse(NpcArrestEvidence.isCurrentThreat(threat, threat, UUID.randomUUID()));
        assertFalse(NpcArrestEvidence.isCurrentThreat(threat,
                threat.withPhase(ActiveIncidentRegistry.Phase.COMMITTED), id));
        assertTrue(NpcArrestEvidence.caseFor(new CrimeWorldData(), threat).isEmpty());
    }

    @Test void incidentFlagsCannotBeChangedThroughCallerOwnedSets() {
        EnumSet<CrimeFlag> flags = EnumSet.of(CrimeFlag.NPC_OFFENDER);
        var incident = new ActiveIncidentRegistry.ActiveIncident(UUID.randomUUID(), THIEF, null,
                null, 0L, flags, ActiveIncidentRegistry.Phase.THREAT);
        flags.add(CrimeFlag.CAUGHT_IN_ACT);
        incident.flags().add(CrimeFlag.MANDATORY_CUSTODY);
        assertEquals(EnumSet.of(CrimeFlag.NPC_OFFENDER), incident.flags());
        assertEquals(incident.flags(), incident.withPhase(ActiveIncidentRegistry.Phase.COMMITTED).flags());
    }
}
