package dev.otectus.mcacrime.job;

import org.jetbrains.annotations.Nullable;

/**
 * The answer from the one occupation transition (0.7.2 §9.3).
 *
 * <p>Four outcomes, not a boolean, because the three failures behave differently. A rejection is
 * final for this attempt; a pending result means the request was recorded and will be validated when
 * the entity loads or arrives, which is what spec §9.4 requires a command to report instead of
 * "assigned"; a suspension means the occupation exists but cannot be honoured right now.
 */
public record OccupationTransitionResult(Outcome outcome, OccupationTransitionReason reason,
                                         OccupationStatus status, @Nullable String detail) {

    public enum Outcome { COMMITTED, PENDING, REJECTED, SUSPENDED }

    public boolean committed() {
        return outcome == Outcome.COMMITTED;
    }

    public boolean pending() {
        return outcome == Outcome.PENDING;
    }

    public boolean rejected() {
        return outcome == Outcome.REJECTED;
    }

    public boolean suspended() {
        return outcome == Outcome.SUSPENDED;
    }

    public static OccupationTransitionResult committed(OccupationStatus status) {
        return new OccupationTransitionResult(Outcome.COMMITTED, OccupationTransitionReason.OK, status, null);
    }

    public static OccupationTransitionResult pending(OccupationTransitionReason reason) {
        return new OccupationTransitionResult(Outcome.PENDING, reason, OccupationStatus.PENDING, null);
    }

    public static OccupationTransitionResult rejected(OccupationTransitionReason reason) {
        return rejected(reason, null);
    }

    public static OccupationTransitionResult rejected(OccupationTransitionReason reason,
                                                      @Nullable String detail) {
        return new OccupationTransitionResult(Outcome.REJECTED, reason, OccupationStatus.NONE, detail);
    }

    public static OccupationTransitionResult suspended(OccupationTransitionReason reason,
                                                       @Nullable String detail) {
        return new OccupationTransitionResult(Outcome.SUSPENDED, reason, OccupationStatus.SUSPENDED, detail);
    }
}
