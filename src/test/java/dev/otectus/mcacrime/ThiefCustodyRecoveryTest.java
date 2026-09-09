package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.thief.ThiefBehaviorController;
import dev.otectus.mcacrime.ai.thief.ThiefBehaviorService;
import dev.otectus.mcacrime.ai.thief.ThiefState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ThiefCustodyRecoveryTest {
    private ThiefBehaviorController controller() {
        return new ThiefBehaviorController(UUID.randomUUID(),
                ResourceLocation.tryParse("minecraft:overworld"), 10L);
    }

    @Test void aReloadedCaptiveCannotResumeScoutingOrAnOldMugging() {
        var controller = controller();
        controller.setVictim(UUID.randomUUID());
        controller.setTransactionId(UUID.randomUUID());
        assertTrue(ThiefBehaviorService.reconcileCustody(controller, true, 20L, 100L));
        assertEquals(ThiefState.ARRESTED, controller.state());
        assertNull(controller.victimId());
        assertNull(controller.transactionId());
        assertTrue(ThiefBehaviorService.reconcileCustody(controller, true, 30L, 100L));
        assertEquals(20L, controller.stateEnteredAt());
    }

    @Test void releaseMissedWhileUnloadedRecoversIntoCooldownOnce() {
        var controller = controller();
        controller.markGuardIntervention();
        ThiefBehaviorService.reconcileCustody(controller, true, 20L, 100L);
        assertFalse(ThiefBehaviorService.reconcileCustody(controller, false, 30L, 100L));
        assertEquals(ThiefState.COOLDOWN, controller.state());
        assertEquals(130L, controller.cooldownUntil());
        assertFalse(controller.consumeGuardIntervention());
        ThiefBehaviorService.reconcileCustody(controller, false, 40L, 100L);
        assertEquals(130L, controller.cooldownUntil());
    }

    @Test void ordinaryThiefBehaviorIsUnaffectedWithoutCustody() {
        var controller = controller();
        controller.enter(ThiefState.APPROACHING, 20L);
        UUID victim = UUID.randomUUID();
        controller.setVictim(victim);
        assertFalse(ThiefBehaviorService.reconcileCustody(controller, false, 30L, 100L));
        assertEquals(ThiefState.APPROACHING, controller.state());
        assertEquals(victim, controller.victimId());
    }
}
