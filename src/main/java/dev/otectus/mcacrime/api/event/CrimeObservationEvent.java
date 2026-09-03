package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.memory.ObserverRole;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.Event;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * An NPC coming to know about a crime (spec §12.5). Fired server-side on
 * {@code NeoForge.EVENT_BUS}.
 *
 * <p>The payload is UUIDs, {@link ResourceLocation}s and vanilla primitives — never an MCA type and
 * never one of this mod's internal mutable objects. A listener that wants the villager entity looks it
 * up from the observer id; handing one out would let a listener mutate AI state mid-observation.
 *
 * <p>{@link Pre} is cancellable and is the seam for "this NPC should not be able to witness anything",
 * which is a legitimate thing for a companion mod to enforce (a blinded, charmed, or sleeping NPC in
 * some other mod's terms). {@link Post} is a notification: the observation is already stored.
 */
public abstract class CrimeObservationEvent extends Event {

    private final UUID observationId;
    private final UUID incidentId;
    private final UUID observerId;
    private final UUID suspectId;
    @Nullable
    private final UUID victimId;
    private final ResourceLocation actionId;
    private final ObserverRole role;
    private final BlockPos location;
    private final float confidence;

    protected CrimeObservationEvent(UUID observationId, UUID incidentId, UUID observerId, UUID suspectId,
                                    @Nullable UUID victimId, ResourceLocation actionId, ObserverRole role,
                                    BlockPos location, float confidence) {
        this.observationId = observationId;
        this.incidentId = incidentId;
        this.observerId = observerId;
        this.suspectId = suspectId;
        this.victimId = victimId;
        this.actionId = actionId;
        this.role = role;
        this.location = location;
        this.confidence = confidence;
    }

    public UUID getObservationId() {
        return observationId;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getObserverId() {
        return observerId;
    }

    public UUID getSuspectId() {
        return suspectId;
    }

    @Nullable
    public UUID getVictimId() {
        return victimId;
    }

    public ResourceLocation getActionId() {
        return actionId;
    }

    public ObserverRole getRole() {
        return role;
    }

    public BlockPos getLocation() {
        return location;
    }

    /** How sure this observer is, 0..1, after distance and obstruction. */
    public float getConfidence() {
        return confidence;
    }

    /** Cancel to stop this NPC learning about this crime at all. Nothing is stored if cancelled. */
    public static final class Pre extends CrimeObservationEvent implements ICancellableEvent {
        public Pre(UUID observationId, UUID incidentId, UUID observerId, UUID suspectId,
                   @Nullable UUID victimId, ResourceLocation actionId, ObserverRole role,
                   BlockPos location, float confidence) {
            super(observationId, incidentId, observerId, suspectId, victimId, actionId, role, location, confidence);
        }
    }

    /** The observation is stored. Not cancellable. */
    public static final class Post extends CrimeObservationEvent {
        public Post(UUID observationId, UUID incidentId, UUID observerId, UUID suspectId,
                    @Nullable UUID victimId, ResourceLocation actionId, ObserverRole role,
                    BlockPos location, float confidence) {
            super(observationId, incidentId, observerId, suspectId, victimId, actionId, role, location, confidence);
        }
    }
}
