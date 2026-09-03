package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ledger.CaseTransitions;
import dev.otectus.mcacrime.ledger.Resolution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The case disposition table (spec §6.6) — most of what "the law" means in this mod, expressed as a
 * set of allowed moves.
 */
class CaseTransitionTest {

    private static final boolean ORDINARY = false;
    private static final boolean PRIVILEGED = true;

    @Test
    void anOpenCaseCanBePaidServedOrEscaped() {
        assertTrue(CaseTransitions.allowed(Resolution.UNRESOLVED, Resolution.FINED, ORDINARY));
        assertTrue(CaseTransitions.allowed(Resolution.UNRESOLVED, Resolution.SERVED, ORDINARY));
        assertTrue(CaseTransitions.allowed(Resolution.UNRESOLVED, Resolution.ESCAPED, ORDINARY));
        assertTrue(CaseTransitions.allowed(Resolution.UNRESOLVED, Resolution.EXPIRED, ORDINARY));
    }

    /**
     * The rule that stops a quest reward or a dialogue click from quietly wiping a murder. A pardon is
     * a decision somebody makes, not a side effect.
     */
    @Test
    void aPardonAlwaysNeedsPrivilege() {
        assertFalse(CaseTransitions.allowed(Resolution.UNRESOLVED, Resolution.PARDONED, ORDINARY));
        assertTrue(CaseTransitions.allowed(Resolution.UNRESOLVED, Resolution.PARDONED, PRIVILEGED));
        assertFalse(CaseTransitions.allowed(Resolution.ESCAPED, Resolution.PARDONED, ORDINARY));
        assertTrue(CaseTransitions.allowed(Resolution.ESCAPED, Resolution.PARDONED, PRIVILEGED));
    }

    /** Breaking out is not forgiveness: the case stays live and can still end properly. */
    @Test
    void escapedIsStillActionableAndCanStillBeSettled() {
        assertTrue(CaseTransitions.isActionable(Resolution.ESCAPED));
        assertTrue(CaseTransitions.allowed(Resolution.ESCAPED, Resolution.SERVED, ORDINARY));
        assertTrue(CaseTransitions.allowed(Resolution.ESCAPED, Resolution.FINED, ORDINARY));
    }

    @Test
    void onlyOpenAndEscapedCasesAreActionable() {
        assertTrue(CaseTransitions.isActionable(Resolution.UNRESOLVED));
        assertTrue(CaseTransitions.isActionable(Resolution.ESCAPED));
        assertFalse(CaseTransitions.isActionable(Resolution.FINED));
        assertFalse(CaseTransitions.isActionable(Resolution.SERVED));
        assertFalse(CaseTransitions.isActionable(Resolution.PARDONED));
        assertFalse(CaseTransitions.isActionable(Resolution.EXPIRED));
    }

    /** A settled case does not quietly downgrade, reopen, or swap for another final state. */
    @Test
    void aSettledCaseNeedsPrivilegeToMoveAgain() {
        assertFalse(CaseTransitions.allowed(Resolution.FINED, Resolution.SERVED, ORDINARY));
        assertFalse(CaseTransitions.allowed(Resolution.SERVED, Resolution.ESCAPED, ORDINARY));
        assertFalse(CaseTransitions.allowed(Resolution.PARDONED, Resolution.UNRESOLVED, ORDINARY));
        assertFalse(CaseTransitions.allowed(Resolution.FINED, Resolution.UNRESOLVED, ORDINARY));

        assertTrue(CaseTransitions.allowed(Resolution.FINED, Resolution.SERVED, PRIVILEGED));
        assertTrue(CaseTransitions.allowed(Resolution.FINED, Resolution.UNRESOLVED, PRIVILEGED));
    }

    @Test
    void reopeningAnOpenCaseIsMeaningless() {
        assertEquals(CaseTransitions.Outcome.DUPLICATE,
                CaseTransitions.classify(Resolution.UNRESOLVED, Resolution.UNRESOLVED, ORDINARY));
    }

    /**
     * A replay must read as success, not failure. The retrying outbox depends on it — an "already
     * done" answer is what stops a redelivery from becoming a second charge.
     */
    @Test
    void reapplyingTheSameDispositionIsADuplicateSuccess() {
        assertEquals(CaseTransitions.Outcome.DUPLICATE,
                CaseTransitions.classify(Resolution.FINED, Resolution.FINED, ORDINARY));
        assertEquals(CaseTransitions.Outcome.ALLOWED,
                CaseTransitions.classify(Resolution.UNRESOLVED, Resolution.FINED, ORDINARY));
        assertEquals(CaseTransitions.Outcome.REJECTED,
                CaseTransitions.classify(Resolution.FINED, Resolution.SERVED, ORDINARY));
    }

    @Test
    void nullsAreRejectedRatherThanThrowing() {
        assertFalse(CaseTransitions.allowed(null, Resolution.FINED, PRIVILEGED));
        assertFalse(CaseTransitions.allowed(Resolution.UNRESOLVED, null, PRIVILEGED));
        assertEquals(CaseTransitions.Outcome.REJECTED,
                CaseTransitions.classify(null, null, PRIVILEGED));
    }

    @Test
    void finalityMatchesTheActionableSplit() {
        for (Resolution resolution : Resolution.values()) {
            assertEquals(!CaseTransitions.isActionable(resolution), CaseTransitions.isFinal(resolution),
                    resolution + " must be exactly one of actionable or final");
        }
    }
}
