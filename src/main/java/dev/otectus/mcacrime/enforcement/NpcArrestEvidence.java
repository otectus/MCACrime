package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;

import java.util.Optional;
import java.util.UUID;

/** Resolves pursuit provenance without inventing an offense when a report leads to arrest. */
public final class NpcArrestEvidence {
    private NpcArrestEvidence() { }

    public static Optional<CrimeRecord> caseFor(CrimeWorldData data,
                                               ActiveIncidentRegistry.ActiveIncident incident) {
        if (data == null || incident == null || incident.phase() != ActiveIncidentRegistry.Phase.COMMITTED)
            return Optional.empty();
        UUID id = incident.incidentId();
        // Older reporting callers identify a pursuit by report ID. Charge the original case.
        UUID caseId = data.reportsAgainst(incident.offenderId()).stream()
                .filter(report -> report.reportId().equals(id))
                .map(report -> report.incidentId()).findFirst().orElse(id);
        return data.recordById(caseId).filter(CrimeRecord::actionable)
                .filter(record -> record.offender().equals(incident.offenderId()));
    }

    /** A stale threat snapshot cannot become another attempted robbery. */
    public static boolean isCurrentThreat(ActiveIncidentRegistry.ActiveIncident proposed,
                                           ActiveIncidentRegistry.ActiveIncident current, UUID sessionId) {
        return proposed != null && current != null && sessionId != null
                && proposed.phase() == ActiveIncidentRegistry.Phase.THREAT
                && current.phase() == ActiveIncidentRegistry.Phase.THREAT
                && proposed.incidentId().equals(current.incidentId())
                && proposed.incidentId().equals(sessionId)
                && proposed.offenderId().equals(current.offenderId());
    }
}
