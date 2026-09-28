package dev.otectus.mcacrime.detention;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No timer, no redstone, no packet, no scheduled task (0.7.5 M4.10, §3.19).
 *
 * <p>The user's "not automatic" is three separate guarantees, and these are the assertions for the
 * third: an execution is carried out only by a deliberate act of a player or an on-duty guard. Every
 * automatic path fails the same test, and for the same reason — none of them has an actor. The delay
 * window on its own never kills anybody; it only bounds how long a deliberate order stays usable.
 */
class CapitalExecutionAuthorizationTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private UUID subject;
    private UUID actor;
    private BlockPos device;

    @BeforeEach
    void reset() {
        ExecutionAuthorization.clearAll();
        ExecutionAuthorization.bind(null);
        subject = UUID.randomUUID();
        actor = UUID.randomUUID();
        device = new BlockPos(-5, 64, 11);
    }

    @AfterEach
    void cleanUp() {
        ExecutionAuthorization.clearAll();
        ExecutionAuthorization.bind(null);
    }

    private void condemn() {
        ExecutionAuthorization.bind((server, id) -> subject.equals(id));
    }

    // ---------------------------------------------------------------- who may order one

    @Test
    void onlyPlayerOrGuardAtDeviceMayExecute() {
        // A timer, a redstone edge, a comparator, a forged packet and a scheduled task all arrive with
        // no actor at all, and all fail the same branch.
        assertEquals(ExecutionAuthorization.Refusal.NO_ACTOR,
                ExecutionAuthorization.check(true, true, false, false, true, true, false));
        assertEquals(ExecutionAuthorization.Refusal.NONE,
                ExecutionAuthorization.check(true, true, true, false, true, true, false),
                "a player, deliberately");
        assertEquals(ExecutionAuthorization.Refusal.NONE,
                ExecutionAuthorization.check(true, true, false, true, true, true, false),
                "or an on-duty guard, while guardMayExecute is on");
    }

    @Test
    void aGuardIsRefusedWhenTheServerLeavesExecutionToPlayers() {
        assertEquals(ExecutionAuthorization.Refusal.ACTOR_NOT_PERMITTED,
                ExecutionAuthorization.check(true, true, false, true, false, true, false));
        assertEquals(ExecutionAuthorization.Refusal.NONE,
                ExecutionAuthorization.check(true, true, true, true, false, true, false),
                "a player is still a player even when guards may not");
    }

    @Test
    void anUnsentencedSubjectIsRefused() {
        assertEquals(ExecutionAuthorization.Refusal.NOT_CONDEMNED,
                ExecutionAuthorization.check(true, false, true, false, true, true, false));
    }

    @Test
    void theFeatureSwitchOutranksEverything() {
        assertEquals(ExecutionAuthorization.Refusal.FEATURE_DISABLED,
                ExecutionAuthorization.check(false, true, true, true, true, true, false));
    }

    @Test
    void aDeviceIsRequired() {
        assertEquals(ExecutionAuthorization.Refusal.NO_DEVICE,
                ExecutionAuthorization.check(true, true, true, false, true, false, false));
    }

    @Test
    void everyRefusalHasAMessage() {
        for (ExecutionAuthorization.Refusal refusal : ExecutionAuthorization.Refusal.values()) {
            assertTrue(ExecutionAuthorization.messageKey(refusal).startsWith("mcacrime."),
                    refusal.name());
        }
        assertTrue(ExecutionAuthorization.messageKey(null).startsWith("mcacrime."));
    }

    // ---------------------------------------------------------------- the legal gate

    @Test
    void withNoLegalSourceNobodyIsCondemnedAndNothingArms() {
        assertFalse(ExecutionAuthorization.condemned(null, subject),
                "the default answer is the truthful one in a build with no capital sentence in it");
        assertTrue(ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device,
                0L, 1L).isEmpty());
        assertEquals(ExecutionAuthorization.Refusal.NOT_CONDEMNED,
                ExecutionAuthorization.lastRefusal());
    }

    @Test
    void aBrokenLegalSourceNeverAuthorisesADeath() {
        ExecutionAuthorization.bind((server, id) -> {
            throw new IllegalStateException("the ledger is mid-migration");
        });
        assertFalse(ExecutionAuthorization.condemned(null, subject));
        assertTrue(ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device,
                0L, 1L).isEmpty());
    }

    @Test
    void theOrderNamesSubjectDeviceAndActor() {
        condemn();
        var pending = ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device,
                100L, 7L).orElseThrow();
        assertEquals(subject, pending.subject());
        assertEquals(actor, pending.actor());
        assertEquals(device, pending.device());
        assertEquals(7L, pending.generation(), "pinned to the custody episode it belongs to");
        assertTrue(pending.at(OVERWORLD, device));
        assertFalse(pending.at(OVERWORLD, BlockPos.ZERO));
    }

    // ---------------------------------------------------------------- the window

    @Test
    void theDelayWindowAloneNeverKills() {
        condemn();
        var pending = ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device,
                0L, 1L).orElseThrow();
        // Running the clock past the window is the only thing time does to this feature, and all it
        // does is remove the order.
        assertEquals(1, ExecutionAuthorization.expire(pending.expiresAt() + 1L));
        assertTrue(ExecutionAuthorization.all().isEmpty());
        assertFalse(ExecutionAuthorization.authorised(subject, OVERWORLD, device,
                pending.expiresAt() + 1L));
    }

    @Test
    void pardonOrCommutationDuringWindowCancels() {
        condemn();
        ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device, 0L, 1L);
        assertTrue(ExecutionAuthorization.authorised(subject, OVERWORLD, device, 10L));

        assertTrue(ExecutionAuthorization.clear(subject, ExecutionAuthorization.ClearReason.PARDONED)
                .isPresent());
        assertFalse(ExecutionAuthorization.authorised(subject, OVERWORLD, device, 10L),
                "clemency inside the window leaves nobody dead");
        assertTrue(ExecutionAuthorization.clear(subject, ExecutionAuthorization.ClearReason.COMMUTED)
                .isEmpty(), "and clearing twice is a no-op");
    }

    @Test
    void destroyingTheDeviceClearsEveryOrderArmedAtIt() {
        condemn();
        ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device, 0L, 1L);
        assertEquals(1, ExecutionAuthorization.clearAt(OVERWORLD, device,
                ExecutionAuthorization.ClearReason.DEVICE_DESTROYED));
        assertEquals(0, ExecutionAuthorization.clearAt(OVERWORLD, device,
                ExecutionAuthorization.ClearReason.CHUNK_UNLOADED));
        assertEquals(0, ExecutionAuthorization.clearAt(OVERWORLD, null,
                ExecutionAuthorization.ClearReason.DEVICE_DESTROYED));
    }

    @Test
    void theGuardWhoOrderedItDyingClearsIt() {
        condemn();
        ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device, 0L, 1L);
        assertEquals(1, ExecutionAuthorization.clearByActor(actor,
                ExecutionAuthorization.ClearReason.GUARD_DIED));
        assertTrue(ExecutionAuthorization.all().isEmpty());
        assertEquals(0, ExecutionAuthorization.clearByActor(null,
                ExecutionAuthorization.ClearReason.GUARD_DIED));
    }

    @Test
    void anOrderForOneSubjectIsNotAnOrderForAnother() {
        condemn();
        ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device, 0L, 1L);
        UUID bystander = UUID.randomUUID();
        assertFalse(ExecutionAuthorization.authorised(bystander, OVERWORLD, device, 1L));
        assertTrue(ExecutionAuthorization.pending(bystander, 1L).isEmpty());
        assertTrue(ExecutionAuthorization.pending(null, 1L).isEmpty());
    }
}
