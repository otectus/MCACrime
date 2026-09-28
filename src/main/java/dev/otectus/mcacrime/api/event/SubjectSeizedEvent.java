package dev.otectus.mcacrime.api.event;

import net.neoforged.bus.api.Event;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Fired once when a subject has been taken hold of physically: chained, anchored or escorted
 * (0.7.5 M6.4).
 *
 * <p>Again the physical half only. A guard beginning an escort and a kidnapper snapping a chain onto
 * somebody are the same physical event and different legal ones, which is why {@code lawful} is a
 * field rather than two event classes: a companion that treats them alike is at least treating them
 * alike deliberately.
 */
public final class SubjectSeizedEvent extends Event {

    private final UUID subject;
    private final boolean subjectIsPlayer;
    @Nullable
    private final UUID holder;
    private final String kind;
    private final boolean lawful;

    public SubjectSeizedEvent(UUID subject, boolean subjectIsPlayer, @Nullable UUID holder, String kind,
                              boolean lawful) {
        this.subject = subject;
        this.subjectIsPlayer = subjectIsPlayer;
        this.holder = holder;
        this.kind = kind == null ? "" : kind;
        this.lawful = lawful;
    }

    public UUID getSubject() {
        return subject;
    }

    public boolean isSubjectPlayer() {
        return subjectIsPlayer;
    }

    /** The entity on the other end, or null for a fence, a hook or a weighted anchor. */
    @Nullable
    public UUID getHolder() {
        return holder;
    }

    /** {@code chain}, {@code anchor} or {@code escort}. */
    public String getKind() {
        return kind;
    }

    public boolean isLawful() {
        return lawful;
    }
}
