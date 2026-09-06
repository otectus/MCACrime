package dev.otectus.mcacrime;

import dev.otectus.mcacrime.action.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActionSessionManagerTest {
    private static ActionSession session(UUID actor, UUID target, UUID nonce) {
        return new ActionSession(UUID.randomUUID(), nonce, new ResourceLocation("mcacrime", "test"),
                actor, target, new ResourceLocation("minecraft", "overworld"), Vec3.ZERO, 1L, 5);
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

    /**
     * The ticker completing an action the same tick a damage event cancelled it. Both paths pay out,
     * release the target lock and notify listeners, so exactly one of them may run.
     */
    @Test
    void onlyTheFirstTerminalCallEndsASession() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ActionSession session = session(actor, target, UUID.randomUUID());
        assertTrue(ActionSessionManager.begin(session));
        ActionResult first = ActionResult.accepted("first");
        assertTrue(ActionSessionManager.finish(session, first));
        assertFalse(ActionSessionManager.finish(session, ActionResult.accepted("second")));
        assertFalse(ActionSessionManager.cancel(session, CancelReason.DAMAGED));
        assertEquals(first, ActionSessionManager.replay(actor, session.requestNonce()).orElseThrow());
        ActionSessionManager.forgetActor(actor);
    }

    /** The same rule from the other side: a cancelled session cannot then be completed. */
    @Test
    void aCancelledSessionCannotAlsoFinish() {
        UUID actor = UUID.randomUUID();
        ActionSession session = session(actor, UUID.randomUUID(), UUID.randomUUID());
        assertTrue(ActionSessionManager.begin(session));
        assertTrue(ActionSessionManager.cancel(session, CancelReason.MOVED));
        assertFalse(ActionSessionManager.finish(session, ActionResult.accepted("too late")));
        ActionSessionManager.forgetActor(actor);
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
}
