package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mask.MaskHeatLedger;
import dev.otectus.mcacrime.state.PlayerCrimeData.PendingMaskedHeat;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deferred-Heat ledger, which is the only part of the mask that has to be exactly right.
 *
 * <p>A mask defers Heat rather than voiding it, so the one thing that must never happen is a spree
 * costing less than the sum of its crimes because the list ran out of room. The bound is there to keep
 * a save file from growing forever, and it is allowed to lose the <em>attribution</em> of old entries;
 * it is not allowed to lose the Heat.
 */
class MaskHeatLedgerTest {

    private static final ResourceLocation CRIME = ResourceLocation.fromNamespaceAndPath("mcacrime", "harm_villager");

    private static PendingMaskedHeat entry(long heat, long tick) {
        return new PendingMaskedHeat(CRIME, "incident-" + tick, heat, tick);
    }

    @Test
    void deferredEntriesAccumulate() {
        List<PendingMaskedHeat> ledger = new ArrayList<>();
        MaskHeatLedger.defer(ledger, entry(5L, 10L), 32);
        MaskHeatLedger.defer(ledger, entry(7L, 20L), 32);
        assertEquals(2, ledger.size());
        assertEquals(12L, MaskHeatLedger.total(ledger));
    }

    @Test
    void zeroHeatIsNotWorthAnEntry() {
        List<PendingMaskedHeat> ledger = new ArrayList<>();
        MaskHeatLedger.defer(ledger, entry(0L, 10L), 32);
        assertTrue(ledger.isEmpty());
    }

    @Test
    void overflowCoalescesAndKeepsTheTotalExact() {
        List<PendingMaskedHeat> ledger = new ArrayList<>();
        long expected = 0L;
        for (int i = 1; i <= 100; i++) {
            MaskHeatLedger.defer(ledger, entry(i, i), 32);
            expected += i;
        }
        assertEquals(32, ledger.size(), "the bound holds");
        assertEquals(expected, MaskHeatLedger.total(ledger), "and it costs the player nothing");
    }

    @Test
    void theCarryEntryNamesASpreeAndKeepsTheEarliestTick() {
        List<PendingMaskedHeat> ledger = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            MaskHeatLedger.defer(ledger, entry(1L, 100L + i), 4);
        }
        PendingMaskedHeat carry = ledger.get(0);
        assertEquals(MaskHeatLedger.MASKED_SPREE, carry.crimeId());
        assertEquals(101L, carry.tick(), "expiry still measures from the oldest crime it swallowed");
    }

    @Test
    void pruneHonoursTheExpiryWindow() {
        List<PendingMaskedHeat> ledger = new ArrayList<>();
        MaskHeatLedger.defer(ledger, entry(5L, 0L), 32);
        MaskHeatLedger.defer(ledger, entry(5L, 900L), 32);
        MaskHeatLedger.prune(ledger, 1000L, 600L);
        assertEquals(1, ledger.size());
        assertEquals(900L, ledger.get(0).tick());
    }

    @Test
    void anExpiryOfZeroMeansNeverLapses() {
        List<PendingMaskedHeat> ledger = new ArrayList<>();
        MaskHeatLedger.defer(ledger, entry(5L, 0L), 32);
        MaskHeatLedger.prune(ledger, 1_000_000L, 0L);
        assertEquals(1, ledger.size());
    }

    @Test
    void drainTakesEverythingAndEmptiesTheLedger() {
        List<PendingMaskedHeat> ledger = new ArrayList<>();
        MaskHeatLedger.defer(ledger, entry(5L, 10L), 32);
        MaskHeatLedger.defer(ledger, entry(6L, 20L), 32);
        List<PendingMaskedHeat> drained = MaskHeatLedger.drain(ledger);
        assertEquals(2, drained.size());
        assertTrue(ledger.isEmpty());
        assertEquals(List.of(), MaskHeatLedger.drain(ledger));
    }
}
