package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.ActionMenuSession;
import dev.otectus.mcacrime.action.ActionSessionManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a menu authorises, and what a nonce authorises (T41).
 *
 * <p>Both halves of the same hole: a menu id proved a target context and nothing else, and a nonce
 * proved only that the client had sent something before. Neither said which action, so a forged
 * {@code actionId} on a legitimate menu started a row the builder had hidden.
 */
class ActionMenuSessionTest {

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mcacrime", path);
    }

    private static ActionMenuSession menu(Set<ResourceLocation> offered) {
        return new ActionMenuSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 1, 100L, offered);
    }

    @Test
    void onlyTheRowsThatWereSentAreOffered() {
        ActionMenuSession session = menu(Set.of(id("mug"), id("threaten")));
        assertTrue(session.offers(id("mug")));
        assertTrue(session.offers(id("threaten")));
        assertFalse(session.offers(id("restrain")), "an action the menu hid is not authorised by it");
        assertFalse(session.offers(null));
    }

    @Test
    void aMenuWithNoOfferedSetOffersNothing() {
        assertFalse(menu(null).offers(id("mug")));
        assertFalse(menu(Set.of()).offers(id("mug")));
    }

    /** The offered set is defensively copied, so a caller's mutable set cannot grow the authorisation. */
    @Test
    void theOfferedSetIsCopiedAndImmutable() {
        Set<ResourceLocation> mutable = new java.util.HashSet<>(Set.of(id("mug")));
        ActionMenuSession session = menu(mutable);
        mutable.add(id("restrain"));
        assertFalse(session.offers(id("restrain")));
    }

    @Test
    void aNonceIsBoundToTheRequestItWasFirstUsedFor() {
        UUID actor = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        long first = ActionSessionManager.payloadHash(nonce, id("mug"), target, 1);

        assertTrue(ActionSessionManager.claimNonce(actor, nonce, first));
        // An honest retry of the identical request still reaches the ordinary replay cache.
        assertTrue(ActionSessionManager.claimNonce(actor, nonce, first));

        long differentAction = ActionSessionManager.payloadHash(nonce, id("restrain"), target, 1);
        assertNotEquals(first, differentAction);
        assertFalse(ActionSessionManager.claimNonce(actor, nonce, differentAction));

        long differentTarget = ActionSessionManager.payloadHash(nonce, id("mug"), UUID.randomUUID(), 1);
        assertFalse(ActionSessionManager.claimNonce(actor, nonce, differentTarget));

        long differentRevision = ActionSessionManager.payloadHash(nonce, id("mug"), target, 2);
        assertFalse(ActionSessionManager.claimNonce(actor, nonce, differentRevision));

        ActionSessionManager.forgetActor(actor);
    }

    @Test
    void twoActorsMayUseTheSameNonceValue() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        long hash = ActionSessionManager.payloadHash(nonce, id("mug"), UUID.randomUUID(), 1);
        assertTrue(ActionSessionManager.claimNonce(first, nonce, hash));
        assertTrue(ActionSessionManager.claimNonce(second, nonce, hash));
        ActionSessionManager.forgetActor(first);
        ActionSessionManager.forgetActor(second);
    }

    @Test
    void theHashIsUnsignedAndStable() {
        UUID nonce = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        long hash = ActionSessionManager.payloadHash(nonce, id("mug"), target, 3);
        assertEquals(hash, ActionSessionManager.payloadHash(nonce, id("mug"), target, 3));
        assertTrue(hash >= 0L && hash <= 0xFFFFFFFFL);
    }
}
