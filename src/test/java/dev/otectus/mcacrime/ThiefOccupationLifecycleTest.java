package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.OccupationStatus;
import dev.otectus.mcacrime.job.ThiefOccupationLifecycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Thief occupation state machine and its clocks (0.7.2 §10.3–10.4).
 *
 * <p>Spec §10.4 draws a table of situations with different required outcomes, and the difference
 * between two of its rows — a novice losing its station versus an established Thief losing one — is
 * the difference between losing the job and merely losing the workplace. That distinction is what
 * most of this file pins.
 */
class ThiefOccupationLifecycleTest {

    @Test
    void theTimingsAreTheOnesTheSpecAsksFor() {
        assertEquals(1200, ThiefOccupationLifecycle.NOVICE_GRACE_TICKS);
        assertEquals(24000, ThiefOccupationLifecycle.ESTABLISHMENT_TICKS,
                "one Minecraft day of active employment");
        assertEquals(200, ThiefOccupationLifecycle.RETRY_MIN_TICKS);
        assertEquals(400, ThiefOccupationLifecycle.RETRY_MAX_TICKS);
    }

    @Test
    void aBoundNoviceBecomesEstablishedOnlyAfterAVisitAndAFullDay() {
        assertFalse(ThiefOccupationLifecycle.establishmentDue(24000L, 0L),
                "employment without ever reaching the station is not establishment");
        assertFalse(ThiefOccupationLifecycle.establishmentDue(23999L, 500L));
        assertTrue(ThiefOccupationLifecycle.establishmentDue(24000L, 500L));
    }

    @Test
    void aNoviceGraceIsMeasuredFromWhenTheStationWasLost() {
        assertFalse(ThiefOccupationLifecycle.noviceGraceExpired(0L, 100000L),
                "zero means bound, not 'unbound since the beginning of time'");
        assertFalse(ThiefOccupationLifecycle.noviceGraceExpired(1000L, 2199L));
        assertTrue(ThiefOccupationLifecycle.noviceGraceExpired(1000L, 2200L));
        assertFalse(ThiefOccupationLifecycle.noviceGraceExpired(9000L, 10L),
                "a clock that went backwards has not elapsed");
    }

    @Test
    void theTwoBoundStatesAndTheUnboundOneAreResolvedFromTheClaimAndTheMilestone() {
        assertEquals(OccupationStatus.ACTIVE_BOUND_NOVICE, ThiefOccupationLifecycle.resolve(true, false));
        assertEquals(OccupationStatus.ACTIVE_BOUND_ESTABLISHED, ThiefOccupationLifecycle.resolve(true, true));
        assertEquals(OccupationStatus.ESTABLISHED_UNBOUND, ThiefOccupationLifecycle.resolve(false, true));
        assertEquals(OccupationStatus.PENDING, ThiefOccupationLifecycle.resolve(false, false),
                "a novice with no claim is not an employed thief");
    }

    @Test
    void anEstablishedUnboundThiefIsStillEmployedAndMayStillAct() {
        assertTrue(OccupationStatus.ESTABLISHED_UNBOUND.employed());
        assertTrue(OccupationStatus.ESTABLISHED_UNBOUND.mayMug(),
                "'unbound' describes a missing workplace, not an additional job or a suspension");
        assertFalse(OccupationStatus.ESTABLISHED_UNBOUND.bound());
    }

    @Test
    void pendingSuspendedAndRetiredOccupationsCannotMug() {
        assertFalse(OccupationStatus.PENDING.mayMug(),
                "a novice whose claim failed must not use its grace period to operate stationless");
        assertFalse(OccupationStatus.SUSPENDED.mayMug());
        assertFalse(OccupationStatus.RETIRED.mayMug());
        assertFalse(OccupationStatus.NONE.mayMug());
        assertTrue(OccupationStatus.ACTIVE_BOUND_NOVICE.mayMug());
        assertTrue(OccupationStatus.ACTIVE_BOUND_ESTABLISHED.mayMug());
    }

    @Test
    void retriesAreStaggeredWithinTheConfiguredWindow() {
        int a = ThiefOccupationLifecycle.retryDelay(1L);
        int b = ThiefOccupationLifecycle.retryDelay(137L);
        for (long seed : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 12345678L}) {
            int delay = ThiefOccupationLifecycle.retryDelay(seed);
            assertTrue(delay >= ThiefOccupationLifecycle.RETRY_MIN_TICKS
                    && delay <= ThiefOccupationLifecycle.RETRY_MAX_TICKS, "seed " + seed + " -> " + delay);
        }
        assertNotEquals(a, b, "two thieves in one village must not search on the same tick");
    }

    @Test
    void aRetryFiresOncePerWindowRatherThanEveryPass() {
        int delay = 200;
        int interval = ThiefOccupationLifecycle.INTERVAL_TICKS;
        int fired = 0;
        for (long now = 1000L; now < 1000L + delay; now += interval) {
            if (ThiefOccupationLifecycle.retryDue(now, interval, delay, 0)) {
                fired++;
            }
        }
        assertEquals(1, fired, "one search per window, not one per reconciliation pass");
    }

    @Test
    void anImpossibleCadenceNeverFires() {
        assertFalse(ThiefOccupationLifecycle.retryDue(1000L, 20, 0, 0));
        assertFalse(ThiefOccupationLifecycle.retryDue(1000L, 0, 200, 0));
    }
}
