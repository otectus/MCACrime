package dev.otectus.mcacrime.integration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One durable cross-mod write, waiting to be delivered.
 *
 * <h2>Why an outbox exists at all</h2>
 *
 * <p>Two Forge mods cannot commit their {@code SavedData} together. MCA: Crime can write "this player
 * murdered a villager" and MCA: Reputation can write "the village knows", but nothing makes those two
 * writes one transaction — and a crash in the gap leaves the two mods disagreeing about what
 * happened, permanently and silently.
 *
 * <p>So the Crime side commits alone, and records what it still owes the other mod <em>in the same
 * dirty cycle</em>. Delivery happens afterwards, can fail, and is retried. That turns "the server
 * crashed at the wrong moment" from a corruption into a delay.
 *
 * <h2>What the payload may contain</h2>
 *
 * <p>Crime-owned NBT only: resource ids, UUIDs, primitives. Never a companion mod's class name and
 * never a serialised Java object. A pending operation has to survive the companion mod being
 * uninstalled for a boot and reinstalled later, and it cannot do that if reading it back requires a
 * class that is not on the classpath.
 */
public record CrimeIntegrationOperation(UUID operationId, ResourceLocation target, UUID playerId,
                                        @Nullable UUID crimeRecordId, ResourceLocation action,
                                        CompoundTag payload, long createdGameTime, int attempts,
                                        long nextAttemptGameTime, Status status, String lastError) {

    /** Longest error string kept. Enough to diagnose, short enough not to bloat the save. */
    public static final int MAX_ERROR_LENGTH = 200;

    public enum Status {
        /** Not yet delivered. Will be retried. */
        PENDING,
        /** Delivered, or confirmed already-delivered. Pruned after the retention window. */
        COMPLETE,
        /** Given up on. Kept, bounded, for an operator to inspect or retry by hand. */
        DEAD_LETTER;

        public static Status parse(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return PENDING;
            }
        }
    }

    public CrimeIntegrationOperation {
        payload = payload == null ? new CompoundTag() : payload;
        status = status == null ? Status.PENDING : status;
        attempts = Math.max(0, attempts);
        lastError = lastError == null ? "" : truncate(lastError);
    }

    private static String truncate(String raw) {
        return raw.length() <= MAX_ERROR_LENGTH ? raw : raw.substring(0, MAX_ERROR_LENGTH);
    }

    /** A fresh pending operation, due immediately. */
    public static CrimeIntegrationOperation create(UUID operationId, ResourceLocation target,
                                                   UUID playerId, @Nullable UUID crimeRecordId,
                                                   ResourceLocation action, CompoundTag payload,
                                                   long gameTime) {
        return new CrimeIntegrationOperation(operationId, target, playerId, crimeRecordId, action,
                payload, gameTime, 0, gameTime, Status.PENDING, "");
    }

    /** A copy recording one failed attempt and when to try again. */
    public CrimeIntegrationOperation withAttempt(long nextAttempt, String error) {
        return new CrimeIntegrationOperation(operationId, target, playerId, crimeRecordId, action,
                payload, createdGameTime, attempts + 1, nextAttempt, Status.PENDING, error);
    }

    /** A copy marked delivered. */
    public CrimeIntegrationOperation complete() {
        return new CrimeIntegrationOperation(operationId, target, playerId, crimeRecordId, action,
                payload, createdGameTime, attempts + 1, nextAttemptGameTime, Status.COMPLETE, "");
    }

    /** A copy moved to the dead-letter list, with the reason it was given up on. */
    public CrimeIntegrationOperation deadLetter(String reason) {
        return new CrimeIntegrationOperation(operationId, target, playerId, crimeRecordId, action,
                payload, createdGameTime, attempts + 1, nextAttemptGameTime, Status.DEAD_LETTER, reason);
    }

    /** Whether this is pending and its backoff has elapsed. */
    public boolean dueAt(long gameTime) {
        return status == Status.PENDING && gameTime >= nextAttemptGameTime;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("op", operationId);
        tag.putString("target", target.toString());
        tag.putUUID("player", playerId);
        if (crimeRecordId != null) {
            tag.putUUID("record", crimeRecordId);
        }
        tag.putString("action", action.toString());
        tag.put("payload", payload.copy());
        tag.putLong("created", createdGameTime);
        tag.putInt("attempts", attempts);
        tag.putLong("nextAttempt", nextAttemptGameTime);
        tag.putString("status", status.name());
        if (!lastError.isEmpty()) {
            tag.putString("lastError", lastError);
        }
        return tag;
    }

    /**
     * Absent-key tolerant load. Returns null for an entry missing the identity fields, so the caller
     * can skip it — the house rule is that one bad entry never costs the whole store.
     */
    @Nullable
    public static CrimeIntegrationOperation load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("op") || !tag.hasUUID("player")) {
            return null;
        }
        ResourceLocation target = ResourceLocation.tryParse(tag.getString("target"));
        ResourceLocation action = ResourceLocation.tryParse(tag.getString("action"));
        if (target == null || action == null) {
            return null;
        }
        return new CrimeIntegrationOperation(
                tag.getUUID("op"),
                target,
                tag.getUUID("player"),
                tag.hasUUID("record") ? tag.getUUID("record") : null,
                action,
                tag.contains("payload", Tag.TAG_COMPOUND) ? tag.getCompound("payload").copy() : new CompoundTag(),
                tag.getLong("created"),
                tag.getInt("attempts"),
                tag.getLong("nextAttempt"),
                Status.parse(tag.getString("status")),
                tag.getString("lastError"));
    }
}
