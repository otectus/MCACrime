package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.Event;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * An observation reaching an authority (spec §12.5). Fired server-side on
 * {@code NeoForge.EVENT_BUS}.
 *
 * <p>This is the event that matters for law enforcement, not {@link CrimeObservationEvent}: a crime
 * that was seen but never reported gives no guard anywhere a legal basis. Cancelling {@link Pre} is
 * how a companion mod implements bribery, intimidation, or a corrupt jurisdiction — it stops this
 * report, and deliberately not any other observation of the same incident.
 *
 * <p>A null {@link #getJurisdiction()} is the wilderness case: filed, with no village to receive it.
 */
public abstract class CrimeReportEvent extends Event {

    private final UUID reportId;
    private final UUID incidentId;
    private final UUID observationId;
    private final UUID reporterId;
    private final UUID suspectId;
    private final ResourceLocation actionId;
    @Nullable
    private final CrimeCommunityKey jurisdiction;
    private final float confidence;
    private final boolean authoritative;

    protected CrimeReportEvent(UUID reportId, UUID incidentId, UUID observationId, UUID reporterId,
                               UUID suspectId, ResourceLocation actionId,
                               @Nullable CrimeCommunityKey jurisdiction, float confidence,
                               boolean authoritative) {
        this.reportId = reportId;
        this.incidentId = incidentId;
        this.observationId = observationId;
        this.reporterId = reporterId;
        this.suspectId = suspectId;
        this.actionId = actionId;
        this.jurisdiction = jurisdiction;
        this.confidence = confidence;
        this.authoritative = authoritative;
    }

    public UUID getReportId() {
        return reportId;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getObservationId() {
        return observationId;
    }

    /** Who is filing it. For a guard's own direct observation, the guard. */
    public UUID getReporterId() {
        return reporterId;
    }

    public UUID getSuspectId() {
        return suspectId;
    }

    public ResourceLocation getActionId() {
        return actionId;
    }

    /** The receiving community, or null for the wilderness. */
    @Nullable
    public CrimeCommunityKey getJurisdiction() {
        return jurisdiction;
    }

    public float getConfidence() {
        return confidence;
    }

    /** True when a responder observed it directly and had nobody to walk to. */
    public boolean isAuthoritative() {
        return authoritative;
    }

    /** Cancel to suppress this one report. Other observations of the same incident are untouched. */
    public static final class Pre extends CrimeReportEvent implements ICancellableEvent {
        public Pre(UUID reportId, UUID incidentId, UUID observationId, UUID reporterId, UUID suspectId,
                   ResourceLocation actionId, @Nullable CrimeCommunityKey jurisdiction, float confidence,
                   boolean authoritative) {
            super(reportId, incidentId, observationId, reporterId, suspectId, actionId, jurisdiction,
                    confidence, authoritative);
        }
    }

    /** The report is filed. Not cancellable. */
    public static final class Post extends CrimeReportEvent {
        public Post(UUID reportId, UUID incidentId, UUID observationId, UUID reporterId, UUID suspectId,
                    ResourceLocation actionId, @Nullable CrimeCommunityKey jurisdiction, float confidence,
                    boolean authoritative) {
            super(reportId, incidentId, observationId, reporterId, suspectId, actionId, jurisdiction,
                    confidence, authoritative);
        }
    }
}
