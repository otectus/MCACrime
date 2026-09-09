package dev.otectus.mcacrime.integration;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.CrimeIncidentMapping;
import dev.otectus.mcacrime.compat.ReputationBridge;
import dev.otectus.mcacrime.ledger.CrimeResolutionEntry;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/**
 * Where the Crime side hands work to the integration layer.
 *
 * <p>Always loadable, and it names no companion type. The services call these methods right after
 * committing their own change; what actually happens is that an entry goes into the outbox in the
 * same dirty cycle. Nothing here talks to another mod — the pump does that, later, and can retry.
 *
 * <h2>Why the enqueue is unconditional</h2>
 *
 * <p>An operation is queued whenever the crime <em>maps</em> to a civic incident, regardless of
 * whether the companion is currently reachable. A player who uninstalls MCA: Reputation for one boot
 * and puts it back should find their village remembers what happened in between; dropping the work at
 * enqueue time would silently lose it. The pump refuses to deliver while the bridge is down, and the
 * queue is bounded, so the cost of being generous here is a capped list rather than divergent truth.
 */
public final class CrimeIntegrationHooks {

    private CrimeIntegrationHooks() {
    }

    /**
     * Whether the companion mod is going to record this deed canonically, and therefore whether the
     * local village-standing penalty should be skipped.
     *
     * <p><b>Deliberately predictive rather than observed.</b> {@code RelationshipConsequences} is a
     * listener on {@code CrimeCommittedEvent}, and the event is posted before this class has queued
     * anything, so asking "was an operation enqueued?" would always answer no. Being a pure function
     * of config, mapping, and bridge health makes the answer independent of listener order — and
     * testable without a server.
     *
     * <p>The compensating case is handled at the other end: when an operation dead-letters, the pump
     * applies the local penalty after all, so a deed that never reached the companion still costs the
     * player standing somewhere.
     */
    public static boolean willRecordCanonically(ResourceLocation crimeType) {
        return willRecordCanonically(crimeType,
                McaCrimeConfig.COMMON.enableReputation.get(),
                McaCrimeConfig.COMMON.suppressLocalVillagePenalty.get(),
                ReputationBridge.isAvailable(),
                ReputationBridge.holdsAuthority());
    }

    /** The pure form, so the suppression rule can be tested at every combination. */
    public static boolean willRecordCanonically(ResourceLocation crimeType, boolean integrationEnabled,
                                                boolean suppressLocalPenalty, boolean bridgeAvailable,
                                                boolean holdsAuthority) {
        if (!integrationEnabled || !suppressLocalPenalty || !bridgeAvailable) {
            return false;
        }
        if (CrimeIncidentMapping.incidentFor(crimeType).isEmpty()) {
            return false;
        }
        // For the two deeds MCA: Reputation detects natively, holding authority is what decides who
        // records them. Without the claim its own detector is still running, so we must not also
        // record — and must not suppress our local penalty on the strength of a write we will not make.
        return !CrimeIncidentMapping.overlapsNativeDetection(crimeType) || holdsAuthority;
    }

    /**
     * Queues the civic incident for a freshly committed case.
     *
     * <p>Called from inside the commit, before the events are posted, so the record and the operation
     * land in one dirty cycle.
     */
    public static void onCommitted(MinecraftServer server, CrimeRecordView view) {
        if (server == null || view == null || !McaCrimeConfig.COMMON.enableReputation.get()) {
            return;
        }
        // Personal records become public only after identified information reaches an authority.
        if (McaCrimeConfig.COMMON.enableObservations.get()
                && !"jailbreak".equals(view.context().get("detection")) && !"command".equals(view.context().get("detection"))
                && CrimeWorldData.get(server).reportsAgainst(view.offenderId()).stream().noneMatch(report ->
                        report.incidentId().equals(view.id()) && report.supportsArrest(McaCrimeConfig.COMMON.reportConfidenceThreshold.get()))) return;
        Optional<ResourceLocation> incident = CrimeIncidentMapping.incidentFor(view.crimeType());
        if (incident.isEmpty() || view.community().isEmpty()) {
            // No civic meaning, or no community to record it against. A crime in the wilderness is
            // still a crime; it just is not anybody's business.
            return;
        }
        if (CrimeIncidentMapping.overlapsNativeDetection(view.crimeType())
                && !ReputationBridge.holdsAuthority()) {
            // MCA: Reputation is still detecting this deed itself. Queuing it would produce a second
            // incident for one swing the moment the bridge came up.
            return;
        }

        CompoundTag payload = new CompoundTag();
        payload.putString(IntegrationTargets.PAYLOAD_INCIDENT_TYPE, incident.get().toString());
        payload.putString(IntegrationTargets.PAYLOAD_DEDUPE_KEY, dedupeKeyFor(view.id()));
        // The time the deed happened, not the time we get around to delivering it. A replayed
        // operation must not tell the village a year-old murder just occurred.
        payload.putLong(IntegrationTargets.PAYLOAD_GAME_TIME, view.committedGameTime());
        payload.put(IntegrationTargets.PAYLOAD_COMMUNITY, view.community().get().save());

        enqueue(server, CrimeIntegrationOperation.create(UUID.randomUUID(),
                IntegrationTargets.REPUTATION_RECORD_INCIDENT, view.offenderId(), view.id(),
                IntegrationTargets.ACTION_CREATE, payload, view.committedGameTime()));
    }

    /**
     * Queues the civic consequence of a case being settled.
     *
     * <p>Only dispositions that mean something civically produce work. Escaping custody deliberately
     * produces none: the village has not forgiven anything, and the incident must stay active.
     */
    public static void onResolved(MinecraftServer server, CrimeRecordView view, CrimeResolutionEntry entry) {
        if (server == null || view == null || entry == null
                || !McaCrimeConfig.COMMON.enableReputation.get()) {
            return;
        }
        if (view.linkedReputationIncidentId().isEmpty() || view.community().isEmpty()) {
            return;
        }
        Optional<String> status = CrimeIncidentMapping.statusFor(entry.resolution(),
                McaCrimeConfig.COMMON.fineResolutionStatus.get(),
                McaCrimeConfig.COMMON.servedResolutionStatus.get());
        if (status.isEmpty()) {
            return;
        }

        CompoundTag payload = new CompoundTag();
        payload.putUUID(IntegrationTargets.PAYLOAD_INCIDENT_ID, view.linkedReputationIncidentId().get());
        payload.putString(IntegrationTargets.PAYLOAD_STATUS, status.get());
        payload.putString(IntegrationTargets.PAYLOAD_DEDUPE_KEY,
                resolutionDedupeKeyFor(view.id(), entry.revision()));
        payload.putLong(IntegrationTargets.PAYLOAD_GAME_TIME, entry.gameTime());
        payload.put(IntegrationTargets.PAYLOAD_COMMUNITY, view.community().get().save());

        enqueue(server, CrimeIntegrationOperation.create(UUID.randomUUID(),
                IntegrationTargets.REPUTATION_RESOLVE_INCIDENT, view.offenderId(), view.id(),
                IntegrationTargets.ACTION_RESOLVE, payload, entry.gameTime()));
    }

    /** The stable identity of a crime case as the companion mod sees it. */
    public static String dedupeKeyFor(UUID recordId) {
        return "crime:" + recordId;
    }

    /**
     * The stable identity of one resolution step. Includes the revision, so a case that is fined and
     * later corrected produces two distinguishable transactions rather than one that looks replayed.
     */
    public static String resolutionDedupeKeyFor(UUID recordId, long revision) {
        return "crime-resolution:" + recordId + ":" + revision;
    }

    private static void enqueue(MinecraftServer server, CrimeIntegrationOperation operation) {
        if (!CrimeWorldData.get(server).enqueueOperation(operation)) {
            McaCrime.LOGGER.warn("MCA: Crime — the integration outbox is full, so the civic record of crime {} "
                            + "was not queued. The crime itself is committed and unaffected. Check "
                            + "/crime debug integrations for a stuck delivery.",
                    operation.crimeRecordId());
        }
    }
}
