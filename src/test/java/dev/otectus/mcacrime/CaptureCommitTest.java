package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CaptureCommitResult;
import dev.otectus.mcacrime.captivity.CaptureTicker;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.captivity.RestraintReservation;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture commit sequence (T08, T09): nothing is spent unless the record stands.
 *
 * <p>The bug this pins is an ordering one. Consuming the restraint before asking the custody table
 * whether the capture was allowed meant that "somebody already holds them" and "you are over your
 * allowance" — the two refusals only the write can discover — cost the captor a rope and gave them
 * nothing at all. {@link CaptureTicker#commit} is pure precisely so the guarantee can be asserted
 * here rather than trusted: the consume seam counts its calls, and a non-ok result must leave it
 * at zero.
 */
class CaptureCommitTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static CaptureCommitResult write(CrimeWorldData data, UUID captor, UUID captive) {
        return CustodyService.capture(data, captor, captive, true, RestraintType.ROPE, 0L,
                new BlockPos(0, 64, 0), OVERWORLD, 4);
    }

    @Test
    void secondCommitAgainstTheSameTargetIsAlreadyHeldAndSpendsNothing() {
        CrimeWorldData data = new CrimeWorldData();
        UUID target = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtomicInteger consumed = new AtomicInteger();
        RestraintReservation reservation = new RestraintReservation(3, ItemStack.EMPTY);

        CaptureCommitResult one = CaptureTicker.commit(() -> CaptureCommitResult.CAPTURED,
                () -> Optional.of(reservation),
                () -> write(data, first, target),
                r -> consumed.incrementAndGet());
        assertTrue(one.ok());
        assertEquals(1, consumed.get());

        CaptureCommitResult two = CaptureTicker.commit(() -> CaptureCommitResult.CAPTURED,
                () -> Optional.of(reservation),
                () -> write(data, second, target),
                r -> consumed.incrementAndGet());
        assertEquals(CaptureCommitResult.ALREADY_HELD, two);
        assertEquals(1, consumed.get()); // the loser's rope is still in their inventory
        assertEquals(1, data.custodyRecords().size());
        assertTrue(data.getCustody(target).getOwner().isKidnapper(first));
    }

    @Test
    void allowanceRefusalAtCommitTimeSpendsNothing() {
        CrimeWorldData data = new CrimeWorldData();
        UUID captor = UUID.randomUUID();
        assertTrue(CustodyService.capture(data, captor, UUID.randomUUID(), true, RestraintType.ROPE, 0L,
                null, OVERWORLD, 1).ok());

        AtomicInteger consumed = new AtomicInteger();
        CaptureCommitResult result = CaptureTicker.commit(() -> CaptureCommitResult.CAPTURED,
                () -> Optional.of(new RestraintReservation(0, ItemStack.EMPTY)),
                () -> CustodyService.capture(data, captor, UUID.randomUUID(), true, RestraintType.ROPE, 0L,
                        null, OVERWORLD, 1),
                r -> consumed.incrementAndGet());
        assertEquals(CaptureCommitResult.QUOTA_FULL, result);
        assertEquals(0, consumed.get());
        assertEquals(1, data.custodyRecords().size());
    }

    /**
     * A channel whose target walked through a portal, died, or lost its session lease during the cast.
     * The re-check is the first step of the sequence, so neither the reservation nor the write happens.
     */
    @Test
    void aFailedRecheckNeitherReservesNorCapturesNorConsumes() {
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger captured = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();

        for (CaptureCommitResult gate : new CaptureCommitResult[]{CaptureCommitResult.TARGET_INVALID,
                CaptureCommitResult.SESSION_LOST, CaptureCommitResult.ALREADY_HELD}) {
            CaptureCommitResult result = CaptureTicker.commit(() -> gate,
                    () -> {
                        reserved.incrementAndGet();
                        return Optional.of(new RestraintReservation(0, ItemStack.EMPTY));
                    },
                    () -> {
                        captured.incrementAndGet();
                        return CaptureCommitResult.CAPTURED;
                    },
                    r -> consumed.incrementAndGet());
            assertEquals(gate, result);
        }
        assertEquals(0, reserved.get());
        assertEquals(0, captured.get());
        assertEquals(0, consumed.get());
    }

    @Test
    void anEmptyReservationIsRestraintMissingAndNeverCaptures() {
        AtomicInteger captured = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();
        CaptureCommitResult result = CaptureTicker.commit(() -> CaptureCommitResult.CAPTURED,
                Optional::empty,
                () -> {
                    captured.incrementAndGet();
                    return CaptureCommitResult.CAPTURED;
                },
                r -> consumed.incrementAndGet());
        assertEquals(CaptureCommitResult.RESTRAINT_MISSING, result);
        assertFalse(result.ok());
        assertEquals(0, captured.get());
        assertEquals(0, consumed.get());
    }

    @Test
    void selfCaptureIsRefusedByName() {
        CrimeWorldData data = new CrimeWorldData();
        UUID captor = UUID.randomUUID();
        assertEquals(CaptureCommitResult.TARGET_INVALID,
                CustodyService.capture(data, captor, captor, true, RestraintType.ROPE, 0L, null, OVERWORLD, 4));
        assertEquals(CaptureCommitResult.TARGET_INVALID,
                CustodyService.captureLawful(data, null, false, CustodyOwner.none(), RestraintType.NONE,
                        0L, null, OVERWORLD));
    }
}
