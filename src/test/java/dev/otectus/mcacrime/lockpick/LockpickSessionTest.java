package dev.otectus.mcacrime.lockpick;

import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.restraint.SessionKind;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's own lockpick session: the meter, the phases and every forged input it refuses (M3.3).
 *
 * <p>This is where the headline upstream exploit is closed. There, the client decides the outcome and
 * names both the actor and the victim; a message saying "I succeeded" is obeyed. Here there is no such
 * message: the only thing a client can send is an angle and a phase number, and both are scored
 * against state it cannot see.
 */
class LockpickSessionTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation PICK = ResourceLocation.fromNamespaceAndPath("mcacrime", "lockpick");
    private static final int DIVISOR = 200;

    private static LockpickSession session(LockpickTarget target, LockpickProfile profile,
                                           long targetRevision) {
        return new LockpickSession(1L, UUID.randomUUID(), target, targetRevision, OVERWORLD, PICK,
                1000L, profile.pick(), 90_000, 0L);
    }

    private static LockpickSession padlockSession(UUID lockId, long bindingRevision) {
        return session(LockpickTarget.padlock(lockId, bindingRevision, UUID.randomUUID(),
                new BlockPos(4, 64, 4)), LockpickProfile.PADLOCK, 1L);
    }

    @Test
    void aSessionStartsOnTheSourcesMeterAndIsALockpickSession() {
        LockpickSession session = padlockSession(UUID.randomUUID(), 1L);
        assertEquals(LockpickProfile.START_METER, session.meter());
        assertEquals(SessionKind.LOCKPICK, session.kind());
        assertFalse(session.finished());
        assertFalse(session.won());
        assertFalse(session.failed());
    }

    @Test
    void forgedSuccessTargetAndDurability() {
        LockpickSession session = padlockSession(UUID.randomUUID(), 1L);

        // There is no "I won" input to send. The only thing a client can claim is a phase and an
        // angle, and a phase it is not on is refused outright.
        assertEquals(LockpickSession.AttemptResult.WRONG_PHASE,
                session.attempt(7, session.phaseTargetMilliDegrees(), 10L, 2, 10.0D, 5.0D));
        assertEquals(LockpickProfile.START_METER, session.meter(), "a refused attempt moves nothing");

        // An angle nowhere near the target is a miss, not a hit, however often it is sent.
        assertEquals(LockpickSession.AttemptResult.MISS,
                session.attempt(0, LockpickSession.wrap(session.phaseTargetMilliDegrees() + 90_000),
                        10L, 2, 10.0D, 5.0D));
        assertEquals(LockpickProfile.START_METER, session.meter());

        // And the rate limit is the server's, not the client's: a second attempt in the same tick
        // window is refused whatever it claims.
        assertEquals(LockpickSession.AttemptResult.TOO_SOON,
                session.attempt(0, session.phaseTargetMilliDegrees(), 11L, 2, 10.0D, 5.0D));

        // The session carries the pick it was opened with, so swapping the tool is detectable at all.
        assertEquals(PICK, session.sourceItem());
    }

    @Test
    void anAlignmentRaisesTheMeterAndMovesToANewPhase() {
        LockpickSession session = padlockSession(UUID.randomUUID(), 1L);
        int target = session.phaseTargetMilliDegrees();

        assertEquals(LockpickSession.AttemptResult.HIT,
                session.attempt(0, target, 10L, 2, 10.0D, 5.0D));
        assertEquals(LockpickProfile.START_METER + LockpickProfile.PADLOCK.progressIncrease(),
                session.meter());
        assertEquals(1, session.phase());

        session.setPhaseTarget(target + 45_000);
        assertNotEquals(target, session.phaseTargetMilliDegrees(),
                "the old phase's winning angle is worthless once the phase moves on");
    }

    @Test
    void enoughAlignmentsWinAndTheSessionThenAcceptsNothing() {
        LockpickSession session = padlockSession(UUID.randomUUID(), 1L);
        long now = 10L;
        LockpickSession.AttemptResult result = LockpickSession.AttemptResult.MISS;
        for (int i = 0; i < 10 && result != LockpickSession.AttemptResult.WIN; i++) {
            result = session.attempt(session.phase(), session.phaseTargetMilliDegrees(), now, 2,
                    10.0D, 5.0D);
            now += 5L;
        }
        assertEquals(LockpickSession.AttemptResult.WIN, result);
        assertTrue(session.won());
        assertTrue(session.finished());
        assertEquals(LockpickSession.AttemptResult.WRONG_PHASE,
                session.attempt(session.phase(), session.phaseTargetMilliDegrees(), now, 2, 10.0D, 5.0D),
                "a finished session cannot be nudged further");
    }

    @Test
    void theMeterDrainsOnServerTicksAndFasterInLaterPhases() {
        LockpickSession session = padlockSession(UUID.randomUUID(), 1L);
        session.drain(20L, DIVISOR);
        int afterFirst = LockpickProfile.START_METER * LockpickSession.SCALE - session.rawMeter();
        assertTrue(afterFirst > 0, "the meter drains while a phase runs");

        session.attempt(0, session.phaseTargetMilliDegrees(), 21L, 2, 10.0D, 5.0D);
        int before = session.rawMeter();
        session.drain(41L, DIVISOR);
        int afterSecond = before - session.rawMeter();
        assertTrue(afterSecond > afterFirst, "phase two drains faster than phase one");
    }

    @Test
    void anEmptyMeterFailsExactlyOnce() {
        LockpickSession session = padlockSession(UUID.randomUUID(), 1L);
        assertFalse(session.drain(1L, DIVISOR));
        assertTrue(session.drain(100_000L, 20), "a long enough drain empties it");
        assertTrue(session.failed());
        assertFalse(session.drain(200_000L, 20), "and it cannot fail a second time");
    }

    @Test
    void theAcceptanceWindowIsAsymmetricAndWraps() {
        // The source's window: ten degrees below the target through five above.
        assertTrue(LockpickSession.within(91_000, 100_000, 10.0D, 5.0D), "nine degrees below is a hit");
        assertFalse(LockpickSession.within(89_000, 100_000, 10.0D, 5.0D), "eleven below is not");
        assertTrue(LockpickSession.within(104_000, 100_000, 10.0D, 5.0D), "four degrees above is a hit");
        assertFalse(LockpickSession.within(106_000, 100_000, 10.0D, 5.0D), "six above is not");

        // A target near zero behaves exactly like any other target.
        assertTrue(LockpickSession.within(355_000, 1_000, 10.0D, 5.0D));
        assertTrue(LockpickSession.within(5_000, 1_000, 10.0D, 5.0D));
        assertFalse(LockpickSession.within(180_000, 1_000, 10.0D, 5.0D));
    }

    @Test
    void anglesOutsideOneTurnAreWrappedRatherThanAccepted() {
        assertEquals(1_000, LockpickSession.wrap(361_000));
        assertEquals(359_000, LockpickSession.wrap(-1_000));
        assertEquals(0, LockpickSession.wrap(LockpickSession.FULL_TURN_MILLI));
    }

    @Test
    void lockReplacedAtSameCoordinatesMidPick() {
        UUID original = UUID.randomUUID();
        BlockPos pos = new BlockPos(8, 64, 8);
        LockpickSession session = session(LockpickTarget.block(original, 1L, pos),
                LockpickProfile.CELL_DOOR, 1L);

        // A new lock at the same position is a different identity, so the session's pinned target no
        // longer describes what is there. Coordinates were never the identity.
        LockpickTarget replacement = LockpickTarget.block(UUID.randomUUID(), 1L, pos);
        assertEquals(pos, replacement.position().orElseThrow());
        assertNotEquals(session.lockTarget().lockId(), replacement.lockId());

        // And a rekey of the same lock is caught by the binding revision rather than the id.
        LockpickTarget rekeyed = LockpickTarget.block(original, 2L, pos);
        assertEquals(session.lockTarget().lockId(), rekeyed.lockId());
        assertNotEquals(session.lockTarget().bindingRevision(), rekeyed.bindingRevision());
    }

    @Test
    void aRestraintSessionPinsTheWornInstanceRatherThanTheSlot() {
        UUID subject = UUID.randomUUID();
        UUID worn = UUID.randomUUID();
        LockpickTarget target = LockpickTarget.restraint(subject, RestraintSlot.ARMS, worn);
        LockpickSession session = session(target, LockpickProfile.HANDCUFFS, 3L);

        assertEquals(subject, session.target(), "the registry cancels by subject");
        assertEquals(worn, session.lockTarget().instanceId());
        // Cuffs removed and replaced during the pick are a different instance, so a session aimed at
        // the first pair cannot open the second.
        assertNotEquals(worn, LockpickTarget.restraint(subject, RestraintSlot.ARMS, UUID.randomUUID())
                .instanceId());
    }

    @Test
    void cancellingEndsItWithoutWinningOrFailing() {
        LockpickSession session = padlockSession(UUID.randomUUID(), 1L);
        session.finish();
        assertTrue(session.finished());
        assertFalse(session.won());
        assertFalse(session.failed(), "an abandoned session is not a failed one");
    }

    @Test
    void expiryIsExclusiveSoADeadSessionIsNeverLive() {
        LockpickSession session = padlockSession(UUID.randomUUID(), 1L);
        assertTrue(session.live(999L));
        assertFalse(session.live(1000L));
        assertFalse(session.live(1001L));
    }
}
