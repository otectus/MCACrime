package dev.otectus.mcacrime.api.event;

import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fired once when an arrest has bound a capital sentence (0.7.5 §3.19, M6.8).
 *
 * <p>Post-commit and not cancellable: the binding has already happened, and a listener that could
 * veto it would leave the prisoner in custody under a sentence nothing recorded. A companion mod
 * that wants to prevent capital sentences turns the feature off.
 *
 * <p>Being assigned one is not being about to die. There is no timer behind this event: with no
 * usable device the subject simply serves the holding term in a cell, and clemency can end it at any
 * point.
 */
public final class CapitalSentenceAssignedEvent extends Event {

    private final UUID subject;
    private final boolean subjectIsPlayer;
    private final UUID sentenceId;
    @Nullable
    private final UUID arrestingAuthority;
    private final long holdingTicks;

    public CapitalSentenceAssignedEvent(UUID subject, boolean subjectIsPlayer, UUID sentenceId,
                                        @Nullable UUID arrestingAuthority, long holdingTicks) {
        this.subject = subject;
        this.subjectIsPlayer = subjectIsPlayer;
        this.sentenceId = sentenceId;
        this.arrestingAuthority = arrestingAuthority;
        this.holdingTicks = Math.max(0L, holdingTicks);
    }

    public UUID getSubject() {
        return subject;
    }

    public boolean isSubjectPlayer() {
        return subjectIsPlayer;
    }

    public UUID getSentenceId() {
        return sentenceId;
    }

    /** The guard or operator who made the arrest, when there was one. */
    @Nullable
    public UUID getArrestingAuthority() {
        return arrestingAuthority;
    }

    /** The custodial term the sentence still carries; a capital sentence is served in a cell. */
    public long getHoldingTicks() {
        return holdingTicks;
    }
}
