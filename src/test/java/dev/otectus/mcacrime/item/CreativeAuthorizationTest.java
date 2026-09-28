package dev.otectus.mcacrime.item;

import dev.otectus.mcacrime.item.creative.CreativeAuthorization;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may use the three operator restraint tools (0.7.5 M2.1).
 *
 * <p>The defect this guards is upstream's, and it is an absence rather than a mistake:
 * {@code CreativeRestraintCutter.interactLivingEntity} performs no authorisation check at all, so
 * possession of the item <em>is</em> the permission. On any server where such an item has escaped
 * into survival — a creative player's death drop, a shared shulker, a datapack that hands one out —
 * every restraint in the world becomes removable by whoever picked it up, including the cuffs on a
 * lawfully arrested prisoner.
 *
 * <p>So the rule is a function of what the <em>server</em> knows about the actor, and the four cases
 * below are the whole of it. Refusing null is part of the rule, not defensive noise: a call with no
 * resolved sender is a call whose identity the connection never established.
 */
class CreativeAuthorizationTest {

    @Test
    void creativeModeAlone_isEnough() {
        // What the items exist for. A creative player has every item in the game already.
        assertTrue(CreativeAuthorization.permits(true, 0));
    }

    @Test
    void operatorPermissionAlone_isEnough() {
        // The same bar /crime administration uses, so one grant covers both.
        assertTrue(CreativeAuthorization.permits(false, CreativeAuthorization.OPERATOR_LEVEL));
        assertTrue(CreativeAuthorization.permits(false, 4));
    }

    @Test
    void survivalWithoutPermission_isRefused() {
        // The case upstream gets wrong: holding the item is not authority.
        assertFalse(CreativeAuthorization.permits(false, 0));
        assertFalse(CreativeAuthorization.permits(false, CreativeAuthorization.OPERATOR_LEVEL - 1));
    }

    @Test
    void noResolvedSender_isRefused() {
        assertFalse(CreativeAuthorization.permits(null));
    }
}
