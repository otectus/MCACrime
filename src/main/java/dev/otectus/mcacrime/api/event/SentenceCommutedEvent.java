package dev.otectus.mcacrime.api.event;

import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fired once when clemency has ended a capital sentence (0.7.5 §3.19, M6.8).
 *
 * <p>Both forms are here rather than in two classes, because a listener almost always wants the same
 * thing from either — the person is not going to be executed — and the one that cares about the
 * difference reads {@link #isPardon()}. A commutation keeps the holding term and the cases; a pardon
 * closes the cases as well.
 *
 * <p>Post-commit and not cancellable. Clemency is an explicit privileged transaction, and a mod that
 * could veto one would be able to keep a death sentence alive against an operator's decision.
 */
public final class SentenceCommutedEvent extends Event {

    private final UUID subject;
    private final boolean subjectIsPlayer;
    private final UUID sentenceId;
    @Nullable
    private final UUID grantedBy;
    private final boolean pardon;
    private final long remainingTicks;

    public SentenceCommutedEvent(UUID subject, boolean subjectIsPlayer, UUID sentenceId,
                                 @Nullable UUID grantedBy, boolean pardon, long remainingTicks) {
        this.subject = subject;
        this.subjectIsPlayer = subjectIsPlayer;
        this.sentenceId = sentenceId;
        this.grantedBy = grantedBy;
        this.pardon = pardon;
        this.remainingTicks = Math.max(0L, remainingTicks);
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

    /** Who granted it: an operator, or whoever the API caller named. */
    @Nullable
    public UUID getGrantedBy() {
        return grantedBy;
    }

    /** True for a pardon, which also closes the cases; false for a commutation, which does not. */
    public boolean isPardon() {
        return pardon;
    }

    /** What is left of the holding term. A commutation never shortens it. */
    public long getRemainingTicks() {
        return remainingTicks;
    }
}
