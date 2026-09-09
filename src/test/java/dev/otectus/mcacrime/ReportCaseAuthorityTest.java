package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.memory.CrimeReport;
import dev.otectus.mcacrime.memory.ReportService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReportCaseAuthorityTest {
    private static final UUID SUSPECT = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final ResourceLocation THEFT = ResourceLocation.tryParse("mcacrime:theft");
    private static final ResourceLocation MURDER = ResourceLocation.tryParse("mcacrime:murder");

    private CrimeRecord record(Resolution resolution) {
        return new CrimeRecord(CASE, SUSPECT, null, THEFT, OptionalInt.empty(), true,
                10L, 5L, -5L, 10L, 0L, resolution);
    }

    private CrimeReport report(UUID suspect, ResourceLocation action) {
        return new CrimeReport(UUID.randomUUID(), CASE, UUID.randomUUID(), UUID.randomUUID(),
                suspect, action, null, 20L, 1000L, 1F, true);
    }

    @Test void resolutionImmediatelyRemovesReportAuthority() {
        CrimeWorldData data = new CrimeWorldData();
        CrimeReport report = report(SUSPECT, THEFT);
        data.addRecord(record(Resolution.UNRESOLVED));
        assertTrue(ReportService.hasActionableCase(data, report));
        for (Resolution terminal : new Resolution[]{Resolution.FINED, Resolution.SERVED, Resolution.PARDONED}) {
            data.replaceRecord(record(terminal));
            assertFalse(ReportService.hasActionableCase(data, report), terminal.name());
        }
    }

    @Test void anEscapeLeavesTheOriginalCaseActionable() {
        CrimeWorldData data = new CrimeWorldData();
        data.addRecord(record(Resolution.ESCAPED));
        assertTrue(ReportService.hasActionableCase(data, report(SUSPECT, THEFT)));
    }

    @Test void missingCaseCannotAuthorizeAnArrestEvenWithAuthoritativeTestimony() {
        assertFalse(ReportService.hasActionableCase(new CrimeWorldData(), report(SUSPECT, THEFT)));
    }

    @Test void testimonyMustMatchBothOffenderAndOffense() {
        CrimeWorldData data = new CrimeWorldData();
        data.addRecord(record(Resolution.UNRESOLVED));
        assertFalse(ReportService.hasActionableCase(data, report(UUID.randomUUID(), THEFT)));
        assertFalse(ReportService.hasActionableCase(data, report(SUSPECT, MURDER)));
        assertFalse(ReportService.hasActionableCase(data, report(null, THEFT)));
    }
}
