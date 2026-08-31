package dev.otectus.mcacrime;

import dev.otectus.mcacrime.economy.FineAllocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a fine is split across open cases.
 *
 * <p>The behaviour that matters is that paying settles <em>specific</em> offences. Before, a payment
 * cleared Heat and left every case open, so the ledger could not tell restitution from a coincidence.
 */
class FineAllocationTest {

    private static final long BASE = 8L;
    private static final long PER_HEAT = 1L;

    private static FineAllocation.FineCase openCase(long heat, long assessedFine, long time) {
        return new FineAllocation.FineCase(UUID.randomUUID(), heat, assessedFine, time);
    }

    private static List<FineAllocation.FineCase> oldestFirst(FineAllocation.FineCase... cases) {
        return new ArrayList<>(List.of(cases));
    }

    /** A player carrying an old debt and a fresh complaint should be clearing the old one. */
    @Test
    void casesAreSettledOldestFirst() {
        FineAllocation.FineCase oldest = openCase(10L, 0L, 100L);
        FineAllocation.FineCase middle = openCase(10L, 0L, 500L);
        FineAllocation.FineCase newest = openCase(10L, 0L, 900L);

        FineAllocation.Allocation allocation = FineAllocation.allocate(
                oldestFirst(oldest, middle, newest), 30L, BASE, PER_HEAT, 1.0D, 2, false);

        assertEquals(List.of(oldest.id(), middle.id()), allocation.caseIds());
        assertFalse(allocation.coversAllHeat(), "two of three cases is not the whole debt");
    }

    @Test
    void theCaseLimitBoundsOnePayment() {
        FineAllocation.Allocation allocation = FineAllocation.allocate(
                oldestFirst(openCase(5L, 0L, 1L), openCase(5L, 0L, 2L), openCase(5L, 0L, 3L),
                        openCase(5L, 0L, 4L)),
                20L, BASE, PER_HEAT, 1.0D, 2, false);

        assertEquals(2, allocation.caseIds().size());
    }

    /** Only the Heat those cases produced is cleared; an unrelated spree is still owed. */
    @Test
    void aPartialPaymentClearsOnlyTheAttributableHeat() {
        FineAllocation.Allocation allocation = FineAllocation.allocate(
                oldestFirst(openCase(12L, 0L, 1L), openCase(20L, 0L, 2L)),
                50L, BASE, PER_HEAT, 1.0D, 1, false);

        assertEquals(12L, allocation.heatCleared());
        assertFalse(allocation.coversAllHeat());
    }

    @Test
    void payingEverythingClearsAllHeatAndEveryCase() {
        FineAllocation.FineCase first = openCase(12L, 0L, 1L);
        FineAllocation.FineCase second = openCase(20L, 0L, 2L);

        FineAllocation.Allocation allocation = FineAllocation.allocate(
                oldestFirst(first, second), 50L, BASE, PER_HEAT, 1.0D, 1, true);

        assertEquals(List.of(first.id(), second.id()), allocation.caseIds(),
                "pay-all ignores the per-payment case limit");
        assertEquals(50L, allocation.heatCleared());
        assertTrue(allocation.coversAllHeat());
    }

    /**
     * Cases can account for more Heat than survives decay. Clearing the difference as well would mean
     * handing the player negative Heat, which is not a thing.
     */
    @Test
    void heatClearedNeverExceedsWhatThePlayerHas() {
        FineAllocation.Allocation allocation = FineAllocation.allocate(
                oldestFirst(openCase(40L, 0L, 1L)), 5L, BASE, PER_HEAT, 1.0D, 1, false);

        assertEquals(5L, allocation.heatCleared());
    }

    /** An assessed figure on the record is a decision somebody made; recomputing would discard it. */
    @Test
    void anAssessedFineBeatsTheComputedPrice() {
        FineAllocation.Allocation allocation = FineAllocation.allocate(
                oldestFirst(openCase(10L, 500L, 1L)), 10L, BASE, PER_HEAT, 1.0D, 1, false);

        assertEquals(500L, allocation.totalCost());
    }

    /** Heat with no case behind it — an unwitnessed spree, or cases long since aged out. */
    @Test
    void heatWithNoOpenCaseCanStillBePaidOff() {
        FineAllocation.Allocation allocation = FineAllocation.allocate(
                List.of(), 30L, BASE, PER_HEAT, 1.0D, 3, true);

        assertTrue(allocation.caseIds().isEmpty());
        assertEquals(30L, allocation.heatCleared());
        assertTrue(allocation.totalCost() > 0L, "a payment with nothing to settle still costs something");
    }

    @Test
    void nothingOwedAndNothingPaidIsAnEmptyAllocation() {
        FineAllocation.Allocation allocation = FineAllocation.allocate(
                List.of(), 0L, BASE, PER_HEAT, 1.0D, 3, false);

        assertTrue(allocation.empty());
    }

    // ------------------------------------------------------------------ pricing

    @Test
    void priceScalesWithHeat() {
        assertEquals(BASE, FineAllocation.price(0L, BASE, PER_HEAT, 1.0D));
        assertEquals(BASE + 20L, FineAllocation.price(20L, BASE, PER_HEAT, 1.0D));
    }

    /** A lawful player pays less, but a fine is never free — that would make Heat costless. */
    @Test
    void theBandMultiplierDiscountsButNeverToZero() {
        assertEquals(4L, FineAllocation.price(0L, BASE, PER_HEAT, 0.5D));
        assertEquals(1L, FineAllocation.price(0L, BASE, PER_HEAT, 0.0D));
    }

    @Test
    void negativeHeatIsTreatedAsNone() {
        assertEquals(BASE, FineAllocation.price(-50L, BASE, PER_HEAT, 1.0D));
    }
}
