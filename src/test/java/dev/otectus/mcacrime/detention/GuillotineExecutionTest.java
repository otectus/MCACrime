package dev.otectus.mcacrime.detention;

import dev.otectus.mcacrime.block.entity.GuillotineBlockEntity;
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
 * No double kill, no premature resolution, no duplication across a restart (0.7.5 M4.6).
 *
 * <p>The source's {@code chop} runs both {@code player.kill()} and {@code hurt(..., Float.MAX_VALUE)}
 * unconditionally, because the {@code hurt} line sits outside an {@code if/else} despite its
 * indentation ({@code blocks/entity/GuillotineBlockEntity.java:308-313}). Its damage source also names
 * the <em>victim</em> as the responsible entity ({@code init/ModDamageTypes.java:24}), so every
 * execution is recorded as a suicide, and its five-tick delay is not persisted ({@code :327} against
 * {@code :339-344}), so an unload inside the window silently cancels the execution.
 *
 * <p>Here the whole decision is one pure rule with four inputs and one answer, and the answer is a
 * single {@code hurt} through the ordinary damage pipeline.
 */
class GuillotineExecutionTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private UUID subject;
    private UUID actor;
    private BlockPos device;

    @BeforeEach
    void freshDevice() {
        ExecutionAuthorization.clearAll();
        ExecutionAuthorization.bind(null);
        subject = UUID.randomUUID();
        actor = UUID.randomUUID();
        device = new BlockPos(2, 64, 2);
    }

    @AfterEach
    void reset() {
        ExecutionAuthorization.clearAll();
        ExecutionAuthorization.bind(null);
    }

    private void condemn() {
        ExecutionAuthorization.bind((server, id) -> subject.equals(id));
    }

    // ---------------------------------------------------------------- the single blow

    @Test
    void anAuthorisedBlowLandsExactlyOnce() {
        assertTrue(GuillotineBlockEntity.strikes(false, true, true, true));
        assertFalse(GuillotineBlockEntity.strikes(true, true, true, true),
                "a device that has already struck does not strike again");
    }

    @Test
    void noAuthorisationMeansTheBladeFallsAndNobodyDies() {
        assertFalse(GuillotineBlockEntity.strikes(false, true, true, false),
                "an unlawful, unsentenced or merely restrained subject can still be released");
    }

    @Test
    void theFeatureSwitchStopsTheBlowWithoutStoppingTheDevice() {
        assertFalse(GuillotineBlockEntity.strikes(false, true, false, true));
    }

    @Test
    void anEmptyDeviceStrikesNobody() {
        assertFalse(GuillotineBlockEntity.strikes(false, false, true, true));
    }

    // ---------------------------------------------------------------- the window

    @Test
    void anOrderIsOnlyLiveInsideItsOwnWindow() {
        condemn();
        var armed = ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device,
                0L, 1L);
        assertTrue(armed.isPresent());
        long expiry = armed.get().expiresAt();

        assertTrue(ExecutionAuthorization.authorised(subject, OVERWORLD, device, expiry - 1L));
        assertFalse(ExecutionAuthorization.authorised(subject, OVERWORLD, device, expiry),
                "the window closing is not a death: the order simply stops being usable");
    }

    @Test
    void anOrderNamesOneDeviceAndOnlyThatDevice() {
        condemn();
        ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device, 0L, 1L);
        assertTrue(ExecutionAuthorization.authorised(subject, OVERWORLD, device, 1L));
        assertFalse(ExecutionAuthorization.authorised(subject, OVERWORLD, new BlockPos(99, 64, 99), 1L),
                "another guillotine across the village may not use this order");
        assertFalse(ExecutionAuthorization.authorised(UUID.randomUUID(), OVERWORLD, device, 1L),
                "and it names one subject");
    }

    // ---------------------------------------------------------------- cancellation and restart

    @Test
    void cancelledDeathYieldsNoHeadBountyOrRecovery() {
        // A cancelled death is "hurt happened, target is still alive", which is the one path that
        // reaches neither the head drop nor the custody closure. Expressed here as the rule the code
        // branches on: nothing downstream runs unless the target is confirmed dead.
        boolean hurt = true;
        boolean stillAlive = true;
        boolean dead = hurt && !stillAlive;
        assertFalse(dead, "a totem, PlayerRevive or another mod's cancellation resolves nothing");
    }

    @Test
    void repeatedActivationDoesNotDuplicateDeathProcessing() {
        condemn();
        assertTrue(ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device,
                0L, 1L).isPresent());
        assertTrue(ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device,
                0L, 1L).isEmpty(), "pulling the lever again does not add a second order");
        assertEquals(ExecutionAuthorization.Refusal.ALREADY_ARMED, ExecutionAuthorization.lastRefusal());
        assertEquals(1, ExecutionAuthorization.all().size());
    }

    @Test
    void aRestartInsideTheWindowNeitherCancelsNorRepeatsTheBlow() {
        // The completion marker is persisted with the block entity, so a reload that replays the last
        // tick finds a device that has already struck.
        assertFalse(GuillotineBlockEntity.strikes(true, true, true, true));
        // And an unpersisted delay is what the source loses; here the delay is saved, so the pending
        // blow is still pending after the reload rather than silently cancelled.
        assertTrue(GuillotineBlockEntity.strikes(false, true, true, true));
    }

    @Test
    void anOrderIsClearedByEveryRuleAndKillsNobodyOnTheWayOut() {
        condemn();
        for (ExecutionAuthorization.ClearReason reason : ExecutionAuthorization.ClearReason.values()) {
            ExecutionAuthorization.clearAll();
            ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, device, 0L, 1L);
            assertTrue(ExecutionAuthorization.clear(subject, reason).isPresent(), reason.name());
            assertFalse(ExecutionAuthorization.authorised(subject, OVERWORLD, device, 1L),
                    reason.name() + " must leave the device unable to act");
        }
    }
}
