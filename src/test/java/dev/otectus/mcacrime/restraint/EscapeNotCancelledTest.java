package dev.otectus.mcacrime.restraint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No restriction policy, however composed, may cancel the way out (0.7.5 M2.8).
 *
 * <p>This is the assertion that keeps a restraint from becoming permanent. Every other restriction is
 * a design decision; cancelling the interaction that <em>is</em> the escape attempt is a bug that
 * needs an operator to undo, and it is an easy one to introduce — the handler that blocks "interact
 * with an entity" is the same handler a subject reaching for the person holding their key goes
 * through.
 *
 * <p>Asserted against the most restrictive policy the ten definitions plus a device can produce, so
 * it cannot pass by the composition happening to leave something permitted.
 */
class EscapeNotCancelledTest {

    private static RestrictionPolicy everything() {
        RestrictionPolicy policy = RestrictionPolicy.unrestricted();
        for (RestraintDefinition definition : RestraintDefinitions.all()) {
            policy = policy.and(definition.restrictions());
        }
        return policy;
    }

    @Test
    void noPolicyCancelsAProtectedAction() {
        RestrictionPolicy worst = everything();
        for (ProtectedAction action : ProtectedAction.values()) {
            assertTrue(RestrictionResolver.allows(worst, action),
                    action + " must never be cancellable by a restriction policy");
        }
    }

    @Test
    void theProtectedListCoversTheWholeWayOut() {
        // Named individually, because the list is the contract: struggling, a configured self-escape,
        // the screen that explains why, chat, and care given by somebody else.
        assertTrue(RestrictionResolver.allows(everything(), ProtectedAction.STRUGGLE));
        assertTrue(RestrictionResolver.allows(everything(), ProtectedAction.SELF_ESCAPE));
        assertTrue(RestrictionResolver.allows(everything(), ProtectedAction.STATUS_SCREEN));
        assertTrue(RestrictionResolver.allows(everything(), ProtectedAction.CHAT));
        assertTrue(RestrictionResolver.allows(everything(), ProtectedAction.EXTERNAL_CARE));
    }

    @Test
    void theWorstCaseReallyIsRestrictive() {
        // Guards the test above from passing because nothing was actually forbidden.
        RestrictionPolicy worst = everything();
        assertTrue(!worst.useItem() && !worst.mineBlocks() && !worst.voluntaryMovement()
                && !worst.jump() && worst.obscureVision() && worst.voiceGag());
    }
}
