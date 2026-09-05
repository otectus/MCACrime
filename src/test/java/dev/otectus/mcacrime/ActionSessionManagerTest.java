package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActionSessionManagerTest {
    private static ActionSession session(UUID actor, UUID target, UUID nonce) {
        return new ActionSession(UUID.randomUUID(), nonce, ResourceLocation.fromNamespaceAndPath("mcacrime", "test"),
                actor, target, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), Vec3.ZERO, 1L, 5);
    }

    @Test
    void oneActorAndOneTargetLockAreEnforcedAndReleased() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ActionSession first = session(actor, target, UUID.randomUUID());
        assertTrue(ActionSessionManager.begin(first));
        assertFalse(ActionSessionManager.begin(session(actor, UUID.randomUUID(), UUID.randomUUID())));
        assertFalse(ActionSessionManager.begin(session(UUID.randomUUID(), target, UUID.randomUUID())));
        ActionSessionManager.finish(first, ActionResult.accepted("done"));
        assertTrue(ActionSessionManager.begin(session(UUID.randomUUID(), target, UUID.randomUUID())));
        ActionSessionManager.clearFor(target, CancelReason.CONFLICT);
    }

    @Test
    void nonceReplayReturnsPriorResultWithoutAnotherSession() {
        UUID actor = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ActionSession first = session(actor, UUID.randomUUID(), nonce);
        assertTrue(ActionSessionManager.begin(first));
        ActionResult result = ActionResult.accepted("once");
        ActionSessionManager.finish(first, result);
        assertEquals(result, ActionSessionManager.replay(actor, nonce).orElseThrow());
    }

    /**
     * The outcome HUD used to render the bare key, so a successful mugging read "You rob the villager
     * of %s emeralds." The code stays a bare key for logging and replay; the arguments ride alongside
     * it so {@code message()} can fill the placeholder in.
     */
    @Test
    void anAcceptedResultCarriesItsTranslationArguments() {
        ActionResult plain = ActionResult.accepted("mcacrime.mug.empty");
        assertTrue(plain.args().isEmpty());

        ActionResult withArgs = ActionResult.accepted("mcacrime.mug.success", 7);
        assertEquals("mcacrime.mug.success", withArgs.code());
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class,
                withArgs.message().getContents());
        assertEquals("mcacrime.mug.success", contents.getKey());
        assertArrayEquals(new Object[]{7}, contents.getArgs());
    }
}
