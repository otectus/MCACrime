package dev.otectus.mcacrime.jail;

import dev.otectus.mcacrime.ledger.Resolution;

import org.jetbrains.annotations.Nullable;
import java.util.Collection;

/**
 * What ending a sentence does to the cases and the Heat it answered for (0.7.7).
 *
 * <p>Pure, so the whole table can be pinned without a server. Before this, only a served sentence
 * resolved anything, and nothing touched Heat: a prisoner released with their Heat still above the
 * Wanted line was a legal target the moment the door opened, and was arrested again on the doorstep for
 * the crimes they had just served time for. The captivity cap -- the safety valve for a sentence that has
 * held somebody too long -- resolved nothing at all, so the same cases put the same player back into the
 * same sentence.
 *
 * <ul>
 *   <li>{@link Effect#SERVE}: time served, and the captivity cap, which is time served by another name.
 *       The cases bound to the sentence are marked served and its Heat obligation is settled.</li>
 *   <li>{@link Effect#SETTLE}: bail and a pardon. The payment or the pardon resolves the cases itself;
 *       the release settles the Heat obligation, which neither of them did.</li>
 *   <li>{@link Effect#UNBIND}: an operator's release and a jail that no longer exists. The custody
 *       instance ends and nothing is forgiven: the cases are released from the sentence so a later,
 *       valid arrest assesses them again, and their Heat stays exactly as it was.</li>
 *   <li>{@link Effect#NONE}: an execution, whose own outcome has already settled everything.</li>
 * </ul>
 *
 * <p>Only the sentence's own obligation is ever settled, never the player's whole Heat: what they did
 * from inside the cell, or on the run after an escape that did not end the sentence, is a separate
 * offence with its own Heat, and it survives the release untouched.
 */
public final class SentenceDisposition {

    /** What one release does. */
    public enum Effect { SERVE, SETTLE, UNBIND, NONE }

    private SentenceDisposition() {
    }

    public static Effect effectOf(ReleaseReason reason) {
        if (reason == null) {
            return Effect.NONE;
        }
        return switch (reason) {
            case SENTENCE_SERVED, CAPTIVITY_CAP -> Effect.SERVE;
            case BAILED, PARDON -> Effect.SETTLE;
            case ADMIN, INVALID_JAIL -> Effect.UNBIND;
            case EXECUTED -> Effect.NONE;
        };
    }

    /** How much Heat a release takes off: the obligation, never more than the player still has. */
    public static long settlement(long obligation, long currentHeat) {
        if (obligation <= 0L || currentHeat <= 0L) {
            return 0L;
        }
        return Math.min(obligation, currentHeat);
    }

    /**
     * The obligation of a sentence handed down before 0.7.7 recorded one: the Heat its still-open cases
     * generated, capped by what the player has; a sentence that binds no case answered for Heat alone.
     */
    public static long legacyObligation(long currentHeat, Collection<Long> boundCaseHeat) {
        long heat = Math.max(0L, currentHeat);
        if (boundCaseHeat == null || boundCaseHeat.isEmpty()) {
            return heat;
        }
        long attributable = 0L;
        for (Long generated : boundCaseHeat) {
            if (generated != null && generated > 0L) {
                attributable = dev.otectus.mcacrime.util.SafeMath.addSat(attributable, generated);
            }
        }
        return Math.min(heat, attributable);
    }

    /**
     * Whether a case-bound sentence has nothing left to answer for, and the release that follows.
     *
     * <p>Every case it was handed down for must be closed. A pardon of any of them makes it a pardon;
     * fines covering all of them make it a paid sentence, as bail would. Anything else -- a case still
     * open, a case that merely expired, a sentence that binds no case at all -- keeps the sentence
     * running, because none of those is the law letting the prisoner go.
     *
     * @return the reason to release with, or null to keep serving
     */
    @Nullable
    public static ReleaseReason resolvedBy(@Nullable Collection<Resolution> boundResolutions) {
        if (boundResolutions == null || boundResolutions.isEmpty()) {
            return null;
        }
        boolean pardoned = false;
        boolean allFined = true;
        for (Resolution resolution : boundResolutions) {
            if (resolution == null || resolution == Resolution.UNRESOLVED || resolution == Resolution.ESCAPED) {
                return null;
            }
            pardoned |= resolution == Resolution.PARDONED;
            allFined &= resolution == Resolution.FINED;
        }
        if (pardoned) {
            return ReleaseReason.PARDON;
        }
        return allFined ? ReleaseReason.BAILED : null;
    }
}
