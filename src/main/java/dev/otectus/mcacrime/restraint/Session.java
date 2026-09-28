package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One piece of server-owned work in progress: a struggle, a lockpick, a frisk.
 *
 * <p>Everything a client could otherwise claim is pinned here at creation: who is doing it, what it
 * is being done to, which revision of that thing, which world, and with which item. A packet arrives
 * carrying a session id and intent, and the server compares the rest against this — which is exactly
 * what upstream does not do, and why its lockpick packet can name both the victim and the actor.
 *
 * <p>Transient. Sessions are never persisted: a restart cancels every one of them, which is the
 * correct outcome and also the only safe one, since nothing on the other end survived either.
 *
 * <p>Abstract rather than a record so the lockpick and frisk sessions of M3 and M5 can carry their
 * own progress without a second identity contract to keep in step.
 */
public abstract class Session {

    private final long id;
    private final SessionKind kind;
    private final UUID actor;
    @Nullable
    private final UUID target;
    private final long targetRevision;
    @Nullable
    private final ResourceLocation dimension;
    @Nullable
    private final ResourceLocation sourceItem;
    private final long expiryTick;

    protected Session(long id, SessionKind kind, UUID actor, @Nullable UUID target, long targetRevision,
                      @Nullable ResourceLocation dimension, @Nullable ResourceLocation sourceItem,
                      long expiryTick) {
        this.id = id;
        this.kind = kind == null ? SessionKind.WORK : kind;
        this.actor = actor;
        this.target = target;
        this.targetRevision = targetRevision;
        this.dimension = dimension;
        this.sourceItem = sourceItem;
        this.expiryTick = expiryTick;
    }

    public long id() {
        return id;
    }

    public SessionKind kind() {
        return kind;
    }

    public UUID actor() {
        return actor;
    }

    @Nullable
    public UUID target() {
        return target;
    }

    /** The target's revision when this session opened. A changed target invalidates the session. */
    public long targetRevision() {
        return targetRevision;
    }

    @Nullable
    public ResourceLocation dimension() {
        return dimension;
    }

    /** The item the session was opened with; swapping it away ends the session. */
    @Nullable
    public ResourceLocation sourceItem() {
        return sourceItem;
    }

    public long expiryTick() {
        return expiryTick;
    }

    /** Whether this session is still live at {@code now}. */
    public boolean live(long now) {
        return now < expiryTick;
    }
}
