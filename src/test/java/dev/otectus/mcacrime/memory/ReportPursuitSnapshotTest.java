package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReportPursuitSnapshotTest {
    @Test
    void acceptedReportBuildsPursuitFromCanonicalCaseAfterObservationIsGone() {
        UUID caseId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID suspect = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        ResourceLocation dimensionId = new ResourceLocation("minecraft", "overworld");
        CrimeCommunityKey authority = new CrimeCommunityKey(dimensionId, 4);
        CrimeRecord crimeCase = new CrimeRecord(caseId, suspect, victim, CrimeIds.ATTEMPTED_MUGGING,
                OptionalInt.of(4), authority, true, Set.of(victim), 10L, 0L, 0L, 0L, 0L,
                Resolution.UNRESOLVED, 0L, List.of(), null, Map.of());
        CrimeReport report = new CrimeReport(reportId, caseId, UUID.randomUUID(), reporter, suspect,
                CrimeIds.ATTEMPTED_MUGGING, authority, 20L, 200L, 1.0F, false);

        ReportService.PursuitBasis basis = ReportService.pursuitBasis(report, crimeCase).orElseThrow();
        assertEquals(reportId, basis.reportId());
        assertEquals(suspect, basis.offenderId());
        assertEquals(victim, basis.victimId());
    }

    @Test
    void mismatchedReportCannotInventPursuitEvidence() {
        UUID caseId = UUID.randomUUID();
        UUID suspect = UUID.randomUUID();
        CrimeRecord crimeCase = new CrimeRecord(caseId, suspect, null, CrimeIds.ATTEMPTED_MUGGING,
                OptionalInt.empty(), false, 10L, 0L, 0L, 0L, 0L, Resolution.UNRESOLVED);
        CrimeReport wrongCase = new CrimeReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), suspect, CrimeIds.ATTEMPTED_MUGGING, null, 20L, 200L, 1.0F, false);
        assertTrue(ReportService.pursuitBasis(wrongCase, crimeCase).isEmpty());
    }
}
