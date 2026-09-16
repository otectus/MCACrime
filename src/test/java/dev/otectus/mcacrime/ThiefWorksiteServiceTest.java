package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.ThiefWorksiteService;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounded parts of station discovery (0.7.2 §10.2).
 *
 * <p>Spec's requirements here are all quantities — a bounded radius, a bounded number of path
 * attempts, a short tested timeout, an arrival that means standing there — so these are the
 * assertions that a later tuning pass cannot quietly undo.
 */
class ThiefWorksiteServiceTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    @Test
    void theSearchStaysWithinTheBoundsTheSpecAsksFor() {
        assertEquals(48, ThiefWorksiteService.SEARCH_RADIUS);
        assertEquals(5, ThiefWorksiteService.MAX_PATH_CANDIDATES);
        assertEquals(400, ThiefWorksiteService.RESERVATION_TIMEOUT_TICKS);
    }

    @Test
    void atMostFiveCandidatesArePathTestedAndTheyAreTheNearestFive() {
        List<BlockPos> found = List.of(
                new BlockPos(40, 64, 0), new BlockPos(3, 64, 0), new BlockPos(20, 64, 0),
                new BlockPos(1, 64, 0), new BlockPos(30, 64, 0), new BlockPos(10, 64, 0),
                new BlockPos(45, 64, 0));

        List<BlockPos> chosen = ThiefWorksiteService.chooseCandidates(found, ORIGIN,
                ThiefWorksiteService.MAX_PATH_CANDIDATES);

        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(3, 64, 0), new BlockPos(10, 64, 0),
                new BlockPos(20, 64, 0), new BlockPos(30, 64, 0)), chosen);
    }

    @Test
    void anEmptyOrAbsentSearchProducesNoCandidatesRatherThanThrowing() {
        assertEquals(List.of(), ThiefWorksiteService.chooseCandidates(List.of(), ORIGIN, 5));
        assertEquals(List.of(), ThiefWorksiteService.chooseCandidates(null, ORIGIN, 5));
        assertEquals(List.of(), ThiefWorksiteService.chooseCandidates(List.of(ORIGIN), ORIGIN, 0));
    }

    @Test
    void fewerCandidatesThanTheCapAreAllReturned() {
        assertEquals(2, ThiefWorksiteService.chooseCandidates(
                List.of(new BlockPos(5, 64, 0), new BlockPos(2, 64, 0)), ORIGIN, 5).size());
    }

    @Test
    void aReservationExpiresExactlyAtTheTimeout() {
        assertFalse(ThiefWorksiteService.reservationExpired(1000L, 1399L, 400));
        assertTrue(ThiefWorksiteService.reservationExpired(1000L, 1400L, 400));
    }

    @Test
    void aReservationThatWasNeverTakenNeverExpires() {
        assertFalse(ThiefWorksiteService.reservationExpired(0L, 999999L, 400),
                "zero is 'no reservation', not 'reserved at the dawn of the world'");
    }

    @Test
    void aClockThatWentBackwardsDoesNotExpireEverything() {
        // A restored backup or a /time set must not time out every reservation in the world.
        assertFalse(ThiefWorksiteService.reservationExpired(9000L, 100L, 400));
    }

    @Test
    void arrivalMeansStandingAtTheStationRatherThanSeeingIt() {
        BlockPos station = new BlockPos(10, 64, 10);
        assertTrue(ThiefWorksiteService.arrived(station, 10.5D, 64.5D, 10.5D,
                ThiefWorksiteService.ARRIVAL_DISTANCE));
        assertFalse(ThiefWorksiteService.arrived(station, 18.5D, 64.5D, 10.5D,
                ThiefWorksiteService.ARRIVAL_DISTANCE));
    }

    @Test
    void anAbsentStationIsNeverArrivedAt() {
        assertFalse(ThiefWorksiteService.arrived(null, 0, 0, 0, 2.0D));
    }
}
