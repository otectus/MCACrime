package dev.otectus.mcacrime.detention;

import dev.otectus.mcacrime.config.ConfigValidator;
import dev.otectus.mcacrime.detention.ExecutionAuthorization.Pending;
import dev.otectus.mcacrime.detention.ExecutionAuthorization.StrikeVerdict;
import dev.otectus.mcacrime.enforcement.CondemnedEscortService;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guillotine timing and strike-time authorisation (0.8.0, workstream B).
 *
 * <p>Two faults found by tracing the source and fixed here. First, an order lasted exactly
 * {@code executionDelayTicks}, and the blade takes {@code guillotineActivationDelayTicks} to land: with a
 * blade at least as long as the window (5 against 0 to 5 are both legal) every order expired mid-fall and
 * neither the lever nor a guard ever struck. Second, the strike asked "is there some order for this
 * subject at this device", so a blade still falling after a reload could carry out an order somebody gave
 * later, and an order outlived a release and re-detention of the same subject.
 */
class ExecutionTimingTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final BlockPos DEVICE = new BlockPos(10, 64, 10);

    @AfterEach
    void reset() {
        ExecutionAuthorization.clearAll();
        ExecutionAuthorization.bind(null);
    }

    @Test
    void theEffectiveWindowAlwaysCoversTheBlade() {
        assertEquals(1200, ExecutionAuthorization.effectiveWindow(1200, 5), "a long window is untouched");
        assertEquals(8, ExecutionAuthorization.effectiveWindow(5, 5), "a window no longer than the blade stretches");
        assertEquals(8, ExecutionAuthorization.effectiveWindow(0, 5), "a zero ceremony still lets the blade land");
        assertEquals(203, ExecutionAuthorization.effectiveWindow(20, 200));
        assertTrue(ExecutionAuthorization.windowStretched(5, 5));
        assertFalse(ExecutionAuthorization.windowStretched(1200, 5));
        assertEquals(1200, ExecutionAuthorization.windowTicks(), "documented defaults with no config loaded");
    }

    /**
     * The manual lever: armed at T, the blade's level tick decrements from the next tick, so the strike
     * reads game time T + blade. The order must still be live then, for every legal pair of settings.
     */
    @Test
    void aLeverStrikeLandsInsideItsOrderForEveryLegalSetting() {
        for (int window : new int[] {0, 1, 2, 4, 5, 6, 8, 20, 100, 1200, 72000}) {
            for (int blade = 1; blade <= 200; blade++) {
                long armed = 1000L;
                long expires = armed + ExecutionAuthorization.effectiveWindow(window, blade);
                long strike = armed + blade;
                assertTrue(strike < expires, "window " + window + ", blade " + blade + ": the lever's blow expired");
            }
        }
    }

    /**
     * The guard: the ceremony ends at {@code dropAt}, and the blade then lands up to one tick later than
     * the drop plus its own delay, depending on which tick phase ran the escort.
     */
    @Test
    void aGuardStrikeLandsInsideItsOrderForEveryLegalSetting() {
        for (int window : new int[] {0, 1, 2, 4, 5, 6, 8, 20, 100, 1200, 72000}) {
            for (int blade = 1; blade <= 200; blade++) {
                long armed = 500L;
                int effective = ExecutionAuthorization.effectiveWindow(window, blade);
                long drop = CondemnedEscortService.dropAt(armed, effective, blade);
                assertTrue(drop > armed, "the ceremony is never skipped");
                assertTrue(drop + blade + 1 < armed + effective,
                        "window " + window + ", blade " + blade + ": the guard's blow expired");
            }
        }
    }

    @Test
    void theValidatorSaysWhenTheWindowWasStretched() {
        assertTrue(ConfigValidator.validateExecutionTiming(true, 1200, 5).isEmpty());
        assertEquals(1, ConfigValidator.validateExecutionTiming(true, 5, 5).size());
        assertTrue(ConfigValidator.validateExecutionTiming(true, 5, 5).get(0).contains("kept for 8 ticks"));
        assertTrue(ConfigValidator.validateExecutionTiming(false, 5, 5).isEmpty(), "silent while the feature is off");
    }

    @Test
    void everyArmedOrderHasItsOwnIdentityAndOccupancy() {
        ExecutionAuthorization.bind((server, subject) -> true);
        UUID subject = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID detention = UUID.randomUUID();
        Pending first = ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, DEVICE,
                100L, 3L, detention).orElseThrow();
        ExecutionAuthorization.clear(subject, ExecutionAuthorization.ClearReason.EXPIRED);
        Pending second = ExecutionAuthorization.arm(null, subject, actor, true, false, OVERWORLD, DEVICE,
                110L, 3L, detention).orElseThrow();
        assertNotEquals(first.orderId(), second.orderId());
        assertEquals(detention, second.detentionId());
        assertEquals(110L + ExecutionAuthorization.windowTicks(), second.expiresAt());
    }

    private static StrikeVerdict verdict(Pending order, UUID armedOrder, UUID subject, UUID actor,
                                         UUID occupant, UUID occupancy, long generation, boolean condemned) {
        return ExecutionAuthorization.strikeVerdict(true, true, condemned, order, armedOrder, subject, actor,
                OVERWORLD, DEVICE, occupant, occupancy, generation);
    }

    @Test
    void theStrikeCarriesOutOnlyTheOrderItArmedForTheOccupancyItWasGivenIn() {
        UUID subject = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID occupancy = UUID.randomUUID();
        Pending order = new Pending(subject, OVERWORLD, DEVICE, actor, 0L, 100L, 2L, UUID.randomUUID(), occupancy);

        assertEquals(StrikeVerdict.STRIKE,
                verdict(order, order.orderId(), subject, actor, subject, occupancy, 2L, true));
        assertEquals(StrikeVerdict.DIFFERENT_ORDER,
                verdict(order, null, subject, actor, subject, occupancy, 2L, true),
                "a fall persisted across a reload has no order id and strikes nobody");
        assertEquals(StrikeVerdict.DIFFERENT_ORDER,
                verdict(order, UUID.randomUUID(), subject, actor, subject, occupancy, 2L, true),
                "an old fall cannot borrow a newer order for the same subject");
        assertEquals(StrikeVerdict.WRONG_ACTOR,
                verdict(order, order.orderId(), subject, UUID.randomUUID(), subject, occupancy, 2L, true));
        assertEquals(StrikeVerdict.OCCUPANCY_CHANGED,
                verdict(order, order.orderId(), subject, actor, subject, UUID.randomUUID(), 2L, true),
                "released and put back in: a new custody episode");
        assertEquals(StrikeVerdict.OCCUPANCY_CHANGED,
                verdict(order, order.orderId(), subject, actor, UUID.randomUUID(), occupancy, 2L, true),
                "somebody else now occupies the device");
        assertEquals(StrikeVerdict.OCCUPANCY_CHANGED,
                verdict(order, order.orderId(), subject, actor, subject, occupancy, 3L, true));
        assertEquals(StrikeVerdict.NOT_CONDEMNED,
                verdict(order, order.orderId(), subject, actor, subject, occupancy, 2L, false),
                "a pardon inside the fall stops the blow");
        assertEquals(StrikeVerdict.NO_ORDER,
                verdict(null, order.orderId(), subject, actor, subject, occupancy, 2L, true));
        assertEquals(StrikeVerdict.WRONG_DEVICE, ExecutionAuthorization.strikeVerdict(true, true, true, order,
                order.orderId(), subject, actor, OVERWORLD, DEVICE.above(), subject, occupancy, 2L));
        assertEquals(StrikeVerdict.DISABLED, ExecutionAuthorization.strikeVerdict(false, true, true, order,
                order.orderId(), subject, actor, OVERWORLD, DEVICE, subject, occupancy, 2L));
        assertEquals(StrikeVerdict.NO_TARGET, ExecutionAuthorization.strikeVerdict(true, false, true, order,
                order.orderId(), subject, actor, OVERWORLD, DEVICE, subject, occupancy, 2L));
    }

    @Test
    void anOrderArmedWithoutAnOccupancyIdStillChecksTheOccupantAndGeneration() {
        UUID subject = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Pending legacy = new Pending(subject, OVERWORLD, DEVICE, actor, 0L, 100L, 1L);
        assertEquals(StrikeVerdict.STRIKE,
                verdict(legacy, legacy.orderId(), subject, actor, subject, UUID.randomUUID(), 1L, true));
        assertEquals(StrikeVerdict.OCCUPANCY_CHANGED,
                verdict(legacy, legacy.orderId(), subject, actor, null, null, 1L, true));
    }

    @Test
    void deviceFeedbackSaysAStateOnceRatherThanEveryClick() {
        BlockPos device = new BlockPos(1, 2, 3);
        assertTrue(DeviceFeedback.repeat(device, "Blade raised.", 100L, device, "Blade raised.", 120L));
        assertFalse(DeviceFeedback.repeat(device, "Blade raised.", 100L, device, "Blade raised.",
                100L + DeviceFeedback.REPEAT_TICKS), "said again once the window has passed");
        assertFalse(DeviceFeedback.repeat(device, "Blade raised.", 100L, device, "Secured Ann.", 101L),
                "a new state is always said");
        assertFalse(DeviceFeedback.repeat(device, "Blade raised.", 100L, device.above(), "Blade raised.", 101L),
                "another device is another conversation");
    }
}
