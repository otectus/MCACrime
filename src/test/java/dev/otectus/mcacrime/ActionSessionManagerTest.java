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
