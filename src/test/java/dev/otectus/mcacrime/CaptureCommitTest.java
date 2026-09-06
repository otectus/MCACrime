package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CaptureCommitResult;
import dev.otectus.mcacrime.captivity.CaptureTicker;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.captivity.RestraintReservation;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commit half of a capture (T08, T09): what the custody table refuses, by name, and what the
 * commit sequence spends when it is refused.
 *
 * <p>Both used to be one boolean and one ordering. The refusals were indistinguishable, so a captor
 * whose victim somebody else had already taken was told the same nothing as one over their own
 * allowance — and the restraint was consumed before the table was ever asked, so either refusal cost
 * an item and produced no record. These assert the two halves separately: the reasons against a bare
 * {@link CrimeWorldData}, and the ordering against {@link CaptureTicker#commit} with counted seams.
 */
class CaptureCommitTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final BlockPos HOLD = new BlockPos(4, 64, 8);

    private static CaptureCommitResult capture(CrimeWorldData data, UUID captor, UUID captive, int allowance) {
        return CustodyService.capture(data, captor, captive, true, RestraintType.ROPE, 0L, HOLD, OVERWORLD,
                allowance);
    }

    // ------------------------------------------------------------------ T08: named refusals

    @Test
    void alreadyHeldCaptiveIsRefusedByNameAndNothingChanges() {
        CrimeWorldData data = new CrimeWorldData();
        UUID captive = UUID.randomUUID();
        UUID firstCaptor = UUID.randomUUID();
        assertEquals(CaptureCommitResult.CAPTURED, capture(data, firstCaptor, captive, 4));

        CustodyRecord before = data.getCustody(captive);
        assertEquals(CaptureCommitResult.ALREADY_HELD, capture(data, UUID.randomUUID(), captive, 4));
        assertEquals(1, data.custodyRecords().size());
        // Not merely "a record still exists": the same record, still owned by whoever got there first.
        assertSame(before, data.getCustody(captive));
        assertTrue(data.getCustody(captive).getOwner().isKidnapper(firstCaptor));
    }

    @Test
    void captorAtTheirAllowanceIsRefusedAsQuotaFull() {
        CrimeWorldData data = new CrimeWorldData();
        UUID captor = UUID.randomUUID();
        assertEquals(CaptureCommitResult.CAPTURED, capture(data, captor, UUID.randomUUID(), 2));
        assertEquals(CaptureCommitResult.CAPTURED, capture(data, captor, UUID.randomUUID(), 2));

        UUID third = UUID.randomUUID();
        assertEquals(CaptureCommitResult.QUOTA_FULL, capture(data, captor, third, 2));
        assertFalse(data.isCaptive(third));
        assertEquals(2, data.custodyRecords().size());
    }

    @Test
    void selfCaptureIsTargetInvalid() {
        CrimeWorldData data = new CrimeWorldData();
        UUID captor = UUID.randomUUID();
        assertEquals(CaptureCommitResult.TARGET_INVALID, capture(data, captor, captor, 4));
        assertTrue(data.custodyRecords().isEmpty());
    }

    @Test
    void aLawfulHoldAlsoBlocksTheNextCapture() {
        CrimeWorldData data = new CrimeWorldData();
        UUID captive = UUID.randomUUID();
        assertEquals(CaptureCommitResult.CAPTURED, CustodyService.captureLawful(data, captive, true,
                CustodyOwner.guard(UUID.randomUUID()), RestraintType.CUFFS, 0L, HOLD, OVERWORLD));

        assertEquals(CaptureCommitResult.ALREADY_HELD, capture(data, UUID.randomUUID(), captive, 4));
        assertEquals(CaptureCommitResult.ALREADY_HELD, CustodyService.captureLawful(data, captive, true,
                CustodyOwner.jail(1, HOLD, OVERWORLD), RestraintType.NONE, 0L, HOLD, OVERWORLD));
        assertEquals(1, data.custodyRecords().size());
    }

    // ------------------------------------------------------------------ T09: two captors, one victim

    @Test
    void twoCommitsForOneTargetLeaveTheSecondCaptorEmptyHanded() {
        CrimeWorldData data = new CrimeWorldData();
        UUID captive = UUID.randomUUID();
        UUID winner = UUID.randomUUID();
        UUID loser = UUID.randomUUID();

        // Sequential, because the mutation runs on the server thread: two channels completing in the
        // same tick reach this table one after the other, and the second must find the first's record.
        assertEquals(CaptureCommitResult.CAPTURED, capture(data, winner, captive, 4));
        assertEquals(CaptureCommitResult.ALREADY_HELD, capture(data, loser, captive, 4));

        assertEquals(1, data.custodyRecords().size());
        assertTrue(data.getCustody(captive).getOwner().isKidnapper(winner));
    }

    // ------------------------------------------------------------------ the commit sequence itself

    @Test
    void nothingIsConsumedWhenTheCaptureIsRefused() {
        for (CaptureCommitResult refusal : new CaptureCommitResult[]{CaptureCommitResult.ALREADY_HELD,
                CaptureCommitResult.QUOTA_FULL, CaptureCommitResult.TARGET_INVALID,
                CaptureCommitResult.GATED}) {
            AtomicInteger consumed = new AtomicInteger();
            CaptureCommitResult result = CaptureTicker.commit(
                    () -> CaptureCommitResult.CAPTURED,
                    () -> Optional.of(new RestraintReservation(3, null)),
                    () -> refusal,
                    reservation -> consumed.incrementAndGet());
            assertEquals(refusal, result);
            assertEquals(0, consumed.get(), "a refused capture must not spend the restraint");
        }
    }

    @Test
    void aFailedEligibilityCheckNeverEvenReserves() {
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger captured = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();

        CaptureCommitResult result = CaptureTicker.commit(
                () -> CaptureCommitResult.SESSION_LOST,
                () -> {
                    reserved.incrementAndGet();
                    return Optional.of(new RestraintReservation(0, null));
                },
                () -> {
                    captured.incrementAndGet();
                    return CaptureCommitResult.CAPTURED;
                },
                reservation -> consumed.incrementAndGet());

        assertEquals(CaptureCommitResult.SESSION_LOST, result);
        assertEquals(0, reserved.get());
        assertEquals(0, captured.get());
        assertEquals(0, consumed.get());
    }

    @Test
    void anEmptyReservationIsRestraintMissingAndNeverCaptures() {
        AtomicInteger captured = new AtomicInteger();
        CaptureCommitResult result = CaptureTicker.commit(
                () -> CaptureCommitResult.CAPTURED,
                Optional::empty,
                () -> {
                    captured.incrementAndGet();
                    return CaptureCommitResult.CAPTURED;
                },
                reservation -> {
                });
        assertEquals(CaptureCommitResult.RESTRAINT_MISSING, result);
        assertEquals(0, captured.get());
    }

    @Test
    void theReservationThatWasTakenIsTheOneThatIsSpent() {
        RestraintReservation reservation = new RestraintReservation(7, null);
        AtomicInteger consumed = new AtomicInteger();

        CaptureCommitResult result = CaptureTicker.commit(
                () -> CaptureCommitResult.CAPTURED,
                () -> Optional.of(reservation),
                () -> CaptureCommitResult.CAPTURED,
                spent -> {
                    assertSame(reservation, spent);
                    consumed.incrementAndGet();
                });

        assertTrue(result.ok());
        assertEquals(1, consumed.get());
    }
}
