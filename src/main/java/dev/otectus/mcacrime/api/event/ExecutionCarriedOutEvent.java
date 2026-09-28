package dev.otectus.mcacrime.api.event;

import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fired once when a capital sentence has actually been carried out (0.7.5 §3.19, M6.8).
 *
 * <p>Post-commit, not cancellable, and fired only on a <b>confirmed</b> death: a blade that fell on
 * somebody a totem saved raises nothing at all, because nothing happened. A companion mod can
 * therefore treat this as "this person is dead and the state has settled" without a second check.
 */
public final class ExecutionCarriedOutEvent extends Event {

    private final UUID subject;
    private final boolean subjectIsPlayer;
    @Nullable
    private final UUID actor;
    private final UUID sentenceId;
    private final String releaseReason;
    private final int casesClosed;
    private final boolean possessionsDropped;

    public ExecutionCarriedOutEvent(UUID subject, boolean subjectIsPlayer, @Nullable UUID actor,
                                    UUID sentenceId, String releaseReason, int casesClosed,
                                    boolean possessionsDropped) {
        this.subject = subject;
        this.subjectIsPlayer = subjectIsPlayer;
        this.actor = actor;
        this.sentenceId = sentenceId;
        this.releaseReason = releaseReason == null ? "" : releaseReason;
        this.casesClosed = Math.max(0, casesClosed);
        this.possessionsDropped = possessionsDropped;
    }

    public UUID getSubject() {
        return subject;
    }

    public boolean isSubjectPlayer() {
        return subjectIsPlayer;
    }

    /** Who carried it out: a player, or the guard the order was given by. */
    @Nullable
    public UUID getActor() {
        return actor;
    }

    public UUID getSentenceId() {
        return sentenceId;
    }

    /** {@code executed}. The reason the sentence closed under, as a lowercase id. */
    public String getReleaseReason() {
        return releaseReason;
    }

    public int getCasesClosed() {
        return casesClosed;
    }

    /** Whether the possessions the law was holding were dropped rather than banked. */
    public boolean isPossessionsDropped() {
        return possessionsDropped;
    }
}
