package dev.otectus.mcacrime.ledger;

/**
 * Which disposition changes a crime case is allowed to make (spec §6.6).
 *
 * <p>Pure, static, and deliberately separate from the service that applies it, so the rule can be
 * read and tested on its own. It is a small table, but it encodes most of what "the law" means in
 * this mod:
 *
 * <ul>
 *   <li>An open case can be paid off, served out, or escaped from.</li>
 *   <li>{@code ESCAPED} is <b>not</b> forgiveness. It is still actionable, and it can still later end
 *       in a fine, a served sentence, or a pardon.</li>
 *   <li>A pardon is never automatic. It needs an explicit privileged transaction, because otherwise
 *       any quest reward or dialogue click that resolves a case could quietly wipe a murder.</li>
 *   <li>A settled case does not reopen or downgrade on its own. Correcting one is an administrative
 *       act, and it should look like one.</li>
 * </ul>
 */
public final class CaseTransitions {

    private CaseTransitions() {
    }

    /** Whether {@code resolution} is a terminal disposition rather than an open case. */
    public static boolean isFinal(Resolution resolution) {
        return resolution == Resolution.SERVED
                || resolution == Resolution.FINED
                || resolution == Resolution.PARDONED
                || resolution == Resolution.EXPIRED;
    }

    /**
     * Whether a case is still legally actionable. {@code ESCAPED} counts — a guard should still
     * pursue someone who broke out, and queries for open cases include both by default.
     */
    public static boolean isActionable(Resolution resolution) {
        return resolution == Resolution.UNRESOLVED || resolution == Resolution.ESCAPED;
    }

    /**
     * Whether {@code from → to} is permitted.
     *
     * <p>A same-state transition returns {@code false} here and the caller reports it as a duplicate
     * success rather than a failure: replaying a transaction that already landed is safe, and a
     * retrying outbox depends on it not being an error.
     *
     * @param privileged true only for an explicit administrative or pardon transaction
     */
    public static boolean allowed(Resolution from, Resolution to, boolean privileged) {
        if (from == null || to == null || from == to) {
            return false;
        }
        if (to == Resolution.UNRESOLVED) {
            // Reopening a case is an administrative correction, never ordinary gameplay.
            return privileged;
        }
        if (to == Resolution.PARDONED) {
            return privileged && (from == Resolution.UNRESOLVED || from == Resolution.ESCAPED);
        }
        return switch (from) {
            case UNRESOLVED -> to == Resolution.FINED || to == Resolution.SERVED
                    || to == Resolution.ESCAPED || to == Resolution.EXPIRED;
            // Escaping does not clear the case; any real disposition can still follow.
            case ESCAPED -> to == Resolution.FINED || to == Resolution.SERVED
                    || to == Resolution.EXPIRED;
            // Already settled: only an operator may move it again.
            case SERVED, FINED, PARDONED, EXPIRED -> privileged;
        };
    }

    /**
     * Classifies an attempted transition for the caller's typed result: {@code DUPLICATE} for a
     * replay of the same state, {@code ALLOWED} when the table permits it, {@code REJECTED} otherwise.
     */
    public static Outcome classify(Resolution from, Resolution to, boolean privileged) {
        if (from == null || to == null) {
            return Outcome.REJECTED;
        }
        if (from == to) {
            return Outcome.DUPLICATE;
        }
        return allowed(from, to, privileged) ? Outcome.ALLOWED : Outcome.REJECTED;
    }

    public enum Outcome {
        /** The change may be applied. */
        ALLOWED,
        /** The case is already in that state; treat as a successful no-op. */
        DUPLICATE,
        /** The table forbids it at this privilege level. */
        REJECTED
    }
}
