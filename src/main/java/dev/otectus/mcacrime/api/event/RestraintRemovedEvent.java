package dev.otectus.mcacrime.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fired once after a restraint has come off a subject (0.7.5 M6.4).
 *
 * <p>Removing a restraint is <b>not</b> a release, and this event is not a release notification: a
 * subject may still be chained, still be in a device, still be wearing two other restraints and
 * still be serving a sentence. The legal side has its own events and they mean different things.
 */
public final class RestraintRemovedEvent extends Event {

    /** Why it came off. */
    public enum Cause {
        /** Somebody unlocked or cut it. */
        RELEASED,
        /** The subject struggled out of it. */
        STRUGGLED,
        /** It ran out of durability. */
        BROKEN,
        /** An operator tool or a command cleared it. */
        ADMINISTRATIVE
    }

    private final UUID subject;
    private final boolean subjectIsPlayer;
    @Nullable
    private final UUID actor;
    private final ResourceLocation definitionId;
    private final String slot;
    private final Cause cause;

    public RestraintRemovedEvent(UUID subject, boolean subjectIsPlayer, @Nullable UUID actor,
                                 ResourceLocation definitionId, String slot, Cause cause) {
        this.subject = subject;
        this.subjectIsPlayer = subjectIsPlayer;
        this.actor = actor;
        this.definitionId = definitionId;
        this.slot = slot == null ? "" : slot;
        this.cause = cause == null ? Cause.RELEASED : cause;
    }

    public UUID getSubject() {
        return subject;
    }

    public boolean isSubjectPlayer() {
        return subjectIsPlayer;
    }

    /** Who took it off, or null when nobody did — a break, or a device opening. */
    @Nullable
    public UUID getActor() {
        return actor;
    }

    public ResourceLocation getDefinitionId() {
        return definitionId;
    }

    public String getSlot() {
        return slot;
    }

    public Cause getCause() {
        return cause;
    }
}
