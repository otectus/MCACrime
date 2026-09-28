package dev.otectus.mcacrime.api.event;

import net.neoforged.bus.api.Event;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Fired once when a device stops holding somebody (0.7.5 M6.4).
 *
 * <p>Every ending of a device occupancy comes through here, including the ones nobody chose: a
 * pillory broken open from the inside, a device destroyed, a chunk unloading with somebody in it.
 * That is the point — a companion mod that only heard about deliberate releases would leak
 * occupancy state exactly where MCA: Crime does not.
 */
public final class DetentionOutcomeEvent extends Event {

    /** How the occupancy ended. */
    public enum Outcome {
        /** Somebody opened it. */
        RELEASED,
        /** The occupant forced it open. */
        BROKE_OUT,
        /** The device was destroyed or is no longer standing. */
        DEVICE_GONE,
        /** The occupant died in it. */
        OCCUPANT_DIED,
        /** An execution was carried out at it. */
        EXECUTED
    }

    private final UUID subject;
    private final boolean subjectIsPlayer;
    private final String kind;
    private final Outcome outcome;
    @Nullable
    private final UUID actor;

    public DetentionOutcomeEvent(UUID subject, boolean subjectIsPlayer, String kind, Outcome outcome,
                                 @Nullable UUID actor) {
        this.subject = subject;
        this.subjectIsPlayer = subjectIsPlayer;
        this.kind = kind == null ? "" : kind;
        this.outcome = outcome == null ? Outcome.RELEASED : outcome;
        this.actor = actor;
    }

    public UUID getSubject() {
        return subject;
    }

    public boolean isSubjectPlayer() {
        return subjectIsPlayer;
    }

    /** {@code pillory}, {@code guillotine} or {@code bunk}. */
    public String getKind() {
        return kind;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** Who ended it, when somebody did. */
    @Nullable
    public UUID getActor() {
        return actor;
    }
}
