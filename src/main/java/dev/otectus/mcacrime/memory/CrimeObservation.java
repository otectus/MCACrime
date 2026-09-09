package dev.otectus.mcacrime.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One NPC's knowledge that a specific crime happened (spec §12.1). This is the structure that replaces
 * {@code WitnessChecker.countWitnesses}, and it exists because a count cannot answer any of the
 * questions believable AI actually needs answered: <em>who</em> saw it, whether they saw the actor or
 * only the act, whether they are still able to report it, and whether they may talk about it at all.
 *
 * <p>An observation is created once, at the moment of the act, and never re-derived. Rescanning later
 * would hand knowledge of a crime to a villager who merely wandered past afterwards — the same leak
 * the witness-identity rules already exist to prevent.
 *
 * <p>Immutable, like {@link dev.otectus.mcacrime.ledger.CrimeRecord} and for the same reasons: the
 * store hands these out to callers, value equality is what the round-trip tests assert, and the one
 * field that legitimately changes over a lifetime — {@link #reportState()} — changes once or twice,
 * not every tick.
 */
public record CrimeObservation(UUID observationId,
                               UUID incidentId,
                               UUID observerId,
                               ObserverRole role,
                               @Nullable UUID suspectedActorId,
                               @Nullable UUID victimId,
                               ResourceLocation actionId,
                               ResourceLocation dimension,
                               BlockPos location,
                               long observedAt,
                               float confidence,
                               boolean sawActor,
                               boolean sawAct,
                               boolean heardAct,
                               ReportState reportState,
                               long expiresAt,
                               boolean relayed) {

    public CrimeObservation(UUID id, UUID incident, UUID observer, ObserverRole role, UUID suspect, UUID victim,
                            ResourceLocation action, ResourceLocation dimension, BlockPos location, long at,
                            float confidence, boolean sawActor, boolean sawAct, boolean heardAct, ReportState report, long expires) {
        this(id, incident, observer, role, suspect, victim, action, dimension, location, at, confidence,
                sawActor, sawAct, heardAct, report, expires, false);
    }

    public CrimeObservation {
        role = role == null ? ObserverRole.EYEWITNESS : role;
        reportState = reportState == null ? ReportState.PENDING : reportState;
        location = location == null ? BlockPos.ZERO : location;
        confidence = Float.isFinite(confidence) ? Math.max(0.0F, Math.min(1.0F, confidence)) : 0;
        if (!sawActor && role != ObserverRole.INFORMED) suspectedActorId = null;
        if (suspectedActorId == null) confidence = 0;
        expiresAt = Math.max(0L, expiresAt);
    }

    /** Whether this observation can still become a report. */
    public boolean pending() {
        return reportState == ReportState.PENDING && role.canReport();
    }

    /** Whether the observer could name the offender, as opposed to only knowing something happened. */
    public boolean identifiesActor() {
        return suspectedActorId != null && confidence >= 0.25F;
    }

    public boolean expired(long now) {
        return expiresAt > 0L && now >= expiresAt;
    }

    public CrimeObservation withReportState(ReportState next) {
        return next == reportState ? this : new CrimeObservation(observationId, incidentId, observerId, role,
                suspectedActorId, victimId, actionId, dimension, location, observedAt, confidence,
                sawActor, sawAct, heardAct, next, expiresAt, relayed);
    }

    /** Lowers confidence without changing what was seen — used when knowledge arrives second-hand. */
    public CrimeObservation withConfidence(float next) {
        return new CrimeObservation(observationId, incidentId, observerId, role, suspectedActorId, victimId,
                actionId, dimension, location, observedAt, next, sawActor, sawAct, heardAct,
                reportState, expiresAt, relayed);
    }

    public CrimeObservation withRelayed() {
        return new CrimeObservation(observationId, incidentId, observerId, role, suspectedActorId, victimId,
                actionId, dimension, location, observedAt, confidence, sawActor, sawAct, heardAct, reportState, expiresAt, true);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", observationId);
        tag.putUUID("incident", incidentId);
        tag.putUUID("observer", observerId);
        tag.putString("role", role.name());
        if (suspectedActorId != null) tag.putUUID("suspect", suspectedActorId);
        if (victimId != null) {
            tag.putUUID("victim", victimId);
        }
        tag.putString("action", actionId.toString());
        tag.putString("dim", dimension.toString());
        tag.put("pos", NbtUtils.writeBlockPos(location));
        tag.putLong("at", observedAt);
        tag.putFloat("confidence", confidence);
        tag.putBoolean("sawActor", sawActor);
        tag.putBoolean("sawAct", sawAct);
        tag.putBoolean("heardAct", heardAct);
        tag.putString("report", reportState.name());
        tag.putLong("expires", expiresAt);
        tag.putBoolean("relayed", relayed);
        return tag;
    }

    /**
     * Reads one observation, throwing so the caller can skip exactly this row and keep the rest of the
     * store. A missing identity is unrecoverable: an observation with no observer is not a weaker
     * observation, it is a claim nobody made, so it is dropped rather than filled in with a placeholder.
     */
    public static CrimeObservation load(CompoundTag tag) {
        if (!tag.hasUUID("id") || !tag.hasUUID("incident") || !tag.hasUUID("observer")) {
            throw new IllegalArgumentException("observation is missing an identity");
        }
        ResourceLocation action = ResourceLocation.tryParse(tag.getString("action"));
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dim"));
        if (action == null || dimension == null) {
            throw new IllegalArgumentException("observation has an unparseable id");
        }
        return new CrimeObservation(
                tag.getUUID("id"),
                tag.getUUID("incident"),
                tag.getUUID("observer"),
                ObserverRole.byName(tag.getString("role")),
                tag.hasUUID("suspect") ? tag.getUUID("suspect") : null,
                tag.hasUUID("victim") ? tag.getUUID("victim") : null,
                action,
                dimension,
                tag.contains("pos", Tag.TAG_COMPOUND) ? NbtUtils.readBlockPos(tag.getCompound("pos")) : BlockPos.ZERO,
                tag.getLong("at"),
                tag.getFloat("confidence"),
                tag.getBoolean("sawActor"),
                tag.getBoolean("sawAct"),
                tag.getBoolean("heardAct"),
                ReportState.byName(tag.getString("report")),
                tag.getLong("expires"), tag.getBoolean("relayed"));
    }
}
