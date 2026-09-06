package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.ActionMenuSession;
import dev.otectus.mcacrime.action.ActionSessionManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a menu session actually authorises.
 *
 * <p>Three separate forgeries, because the menu id on its own answers none of them: an action that
 * was never on the menu, a nonce reused for a different request, and a revision from a menu that has
 * been superseded. Each was startable before, and each needed nothing more than an unmodified server
 * reply plus one edited field.
 */
class ActionMenuSessionTest {

    private static final ResourceLocation MUG = new ResourceLocation("mcacrime", "mug");
    private static final ResourceLocation ARREST = new ResourceLocation("mcacrime", "arrest");
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private static ActionMenuSession menu(UUID actor, UUID target, Set<ResourceLocation> offered) {
        return new ActionMenuSession(UUID.randomUUID(), actor, target, OVERWORLD, 1, 100L, offered);
    }

    @Test
    void anActionTheMenuNeverOfferedIsNotAuthorised() {
        ActionMenuSession session = menu(UUID.randomUUID(), UUID.randomUUID(), Set.of(MUG));
        assertTrue(session.offers(MUG));
        assertFalse(session.offers(ARREST), "a hidden row must not be startable through a valid menu id");
        assertFalse(session.offers(null));
    }

    @Test
    void aNonceReusedWithADifferentPayloadIsRejected() {
        UUID actor = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        long first = ActionSessionManager.payloadHash(nonce, MUG, target, 1);
        assertTrue(ActionSessionManager.claimNonce(actor, nonce, first));
        // The honest retry: identical request, same nonce, still allowed through to the replay cache.
        assertTrue(ActionSessionManager.claimNonce(actor, nonce, first));
        long different = ActionSessionManager.payloadHash(nonce, ARREST, target, 1);
        assertFalse(ActionSessionManager.claimNonce(actor, nonce, different));
        assertFalse(ActionSessionManager.claimNonce(actor, nonce,
                ActionSessionManager.payloadHash(nonce, MUG, UUID.randomUUID(), 1)));
        ActionSessionManager.forgetActor(actor);
    }

    @Test
    void aStaleRevisionOrMovedContextIsNotValid() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ActionMenuSession session = menu(actor, target, Set.of(MUG));
        assertTrue(session.valid(actor, target, OVERWORLD, session.id(), 1, 50L));
        assertFalse(session.valid(actor, target, OVERWORLD, session.id(), 2, 50L), "stale revision");
        assertFalse(session.valid(actor, target, OVERWORLD, UUID.randomUUID(), 1, 50L), "another menu");
        assertFalse(session.valid(actor, target, new ResourceLocation("minecraft", "the_nether"),
                session.id(), 1, 50L), "another dimension");
        assertFalse(session.valid(actor, target, OVERWORLD, session.id(), 1, 101L), "expired");
    }
}
