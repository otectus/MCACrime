package dev.otectus.mcacrime.facility;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simultaneous-arrest case, which is the whole reason a reservation is an object with a token
 * rather than a boolean on a cell.
 *
 * <p>Two guards can reach the same cell in the same tick. With a flag, both read "free", both write
 * "occupied", and the second prisoner arrives to find the sentence already running for somebody else.
 * Here the capacity check and the write happen together, so one caller gets a token and the other gets
 * nothing — and the one that got nothing can fall through to the next rung of the arrest ladder.
 */
class CellReservationTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private static FacilityAssignment cell(int capacity) {
        return new FacilityAssignment(UUID.randomUUID(), new TownsteadBuildingRef(OVERWORLD, 3, 7, 12),
                FacilityRole.JAIL_CELL, new BlockPos(5, 64, 5), capacity, "operator", 0L);
    }

    @Test
    void twoArrestsInOneTickProduceExactlyOneReservation() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment facility = cell(1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        Optional<CellReservation> won = CrimeFacilityService.reserve(data, facility, first, 100L, 600L);
        Optional<CellReservation> lost = CrimeFacilityService.reserve(data, facility, second, 100L, 600L);

        assertTrue(won.isPresent(), "the first arrest takes the only slot");
        assertTrue(lost.isEmpty(),
                "the second must be refused, not queued and not silently given the same slot");
        assertEquals(1, data.cellReservationsFor(facility.id(), 100L).size());
    }

    @Test
    void aSecondSlotIsAvailableWhenTheCellHasTwo() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment facility = cell(2);

        CellReservation first = CrimeFacilityService
                .reserve(data, facility, UUID.randomUUID(), 100L, 600L).orElseThrow();
        CellReservation second = CrimeFacilityService
                .reserve(data, facility, UUID.randomUUID(), 100L, 600L).orElseThrow();

        assertNotEquals(first.slot(), second.slot(), "two reservations must never name the same slot");
        assertNotEquals(first.token(), second.token());
        assertTrue(CrimeFacilityService.reserve(data, facility, UUID.randomUUID(), 100L, 600L).isEmpty());
    }

    @Test
    void thesSamePrisonerReservingTwiceKeepsOneSlot() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment facility = cell(2);
        UUID prisoner = UUID.randomUUID();

        CellReservation first = CrimeFacilityService.reserve(data, facility, prisoner, 100L, 600L).orElseThrow();
        CellReservation again = CrimeFacilityService.reserve(data, facility, prisoner, 120L, 600L).orElseThrow();

        assertEquals(first.token(), again.token(),
                "re-running an arrest must not consume a village's cells one at a time");
        assertEquals(1, data.cellReservationsFor(facility.id(), 120L).size());
    }

    @Test
    void consumingSpendsTheSlotAndCannotBeDoneTwice() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment facility = cell(1);
        CellReservation held = CrimeFacilityService
                .reserve(data, facility, UUID.randomUUID(), 100L, 600L).orElseThrow();

        assertTrue(CrimeFacilityService.consume(data, held.token()));
        assertFalse(CrimeFacilityService.consume(data, held.token()),
                "a double-consume must be a no-op rather than freeing somebody else's slot");
        assertTrue(data.cellReservationsFor(facility.id(), 100L).isEmpty());
    }

    @Test
    void releasingGivesTheSlotBackToTheNextArrest() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment facility = cell(1);
        CellReservation held = CrimeFacilityService
                .reserve(data, facility, UUID.randomUUID(), 100L, 600L).orElseThrow();

        assertTrue(CrimeFacilityService.release(data, held.token()));

        assertTrue(CrimeFacilityService.reserve(data, facility, UUID.randomUUID(), 110L, 600L).isPresent(),
                "a released slot is free immediately; waiting for the lease would strand the next arrest");
    }

    @Test
    void anUnknownTokenFreesNothing() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment facility = cell(1);
        CrimeFacilityService.reserve(data, facility, UUID.randomUUID(), 100L, 600L).orElseThrow();

        assertFalse(CrimeFacilityService.release(data, UUID.randomUUID()));
        assertFalse(CrimeFacilityService.consume(data, null));
        assertEquals(1, data.cellReservationsFor(facility.id(), 100L).size(),
                "a caller that never held a token must not be able to free the one somebody else holds");
    }

    @Test
    void aLapsedLeaseFreesTheSlotAndIsPruned() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment facility = cell(1);
        CrimeFacilityService.reserve(data, facility, UUID.randomUUID(), 100L, 50L).orElseThrow();

        assertTrue(data.cellReservationsFor(facility.id(), 140L).size() == 1);
        assertTrue(data.cellReservationsFor(facility.id(), 160L).isEmpty(),
                "an escort that never arrives must not hold a village's only cell for the rest of the save");
        assertTrue(CrimeFacilityService.reserve(data, facility, UUID.randomUUID(), 160L, 600L).isPresent());
        assertEquals(1, data.pruneCellReservations(200L));
    }

    @Test
    void aRoleThatHoldsNobodyCannotBeReservedInto() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment post = FacilityAssignment.of(TownsteadBuildingRef.unbound(OVERWORLD),
                FacilityRole.GUARD_POST, BlockPos.ZERO, "operator", 0L);

        assertTrue(CrimeFacilityService.reserve(data, post, UUID.randomUUID(), 100L, 600L).isEmpty());
    }

    @Test
    void forgettingAFacilityTakesItsReservationsWithIt() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment facility = cell(1);
        data.putFacility(facility);
        CrimeFacilityService.reserve(data, facility, UUID.randomUUID(), 100L, 600L).orElseThrow();

        assertTrue(data.removeFacility(facility.id()));

        assertTrue(data.cellReservations().isEmpty(),
                "a reservation whose facility is gone can never be spent or released by its holder");
    }

    @Test
    void reservationsSurviveARestart() {
        CrimeWorldData data = new CrimeWorldData();
        FacilityAssignment facility = cell(1);
        data.putFacility(facility);
        UUID prisoner = UUID.randomUUID();
        CellReservation held = CrimeFacilityService.reserve(data, facility, prisoner, 100L, 600L).orElseThrow();

        CrimeWorldData reloaded = CrimeWorldData.load(data.save(new net.minecraft.nbt.CompoundTag()));

        CellReservation after = reloaded.cellReservationForPrisoner(prisoner, 100L);
        assertNotNull(after, "an escort in flight across a restart still has a cell held for it");
        assertEquals(held, after);
        assertNotNull(reloaded.facility(facility.id()));
    }
}
