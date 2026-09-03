package dev.otectus.mcacrime.action;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Mutable server-only channel state. The target and dimension can never change after construction. */
public final class ActionSession {
    private final UUID sessionId;
    private final UUID requestNonce;
    private final ResourceLocation actionId;
    private final UUID actorId;
    private final UUID targetId;
    private final ResourceLocation dimension;
    private final Vec3 actorStart;
    private final long startedAt;
    private final int requiredTicks;
    private int progress;
    private boolean pointOfNoReturn;

    public ActionSession(UUID sessionId, UUID requestNonce, ResourceLocation actionId, UUID actorId,
                         UUID targetId, ResourceLocation dimension, Vec3 actorStart, long startedAt,
                         int requiredTicks) {
        this.sessionId = sessionId;
        this.requestNonce = requestNonce;
        this.actionId = actionId;
        this.actorId = actorId;
        this.targetId = targetId;
        this.dimension = dimension;
        this.actorStart = actorStart;
        this.startedAt = startedAt;
        this.requiredTicks = Math.max(1, requiredTicks);
    }

    public UUID sessionId() { return sessionId; }
    public UUID requestNonce() { return requestNonce; }
    public ResourceLocation actionId() { return actionId; }
    public UUID actorId() { return actorId; }
    public UUID targetId() { return targetId; }
    public ResourceLocation dimension() { return dimension; }
    public Vec3 actorStart() { return actorStart; }
    public long startedAt() { return startedAt; }
    public int requiredTicks() { return requiredTicks; }
    public int progress() { return progress; }
    public boolean pointOfNoReturn() { return pointOfNoReturn; }
    public void markPointOfNoReturn() { pointOfNoReturn = true; }
    public boolean advance() { return ++progress >= requiredTicks; }
}
