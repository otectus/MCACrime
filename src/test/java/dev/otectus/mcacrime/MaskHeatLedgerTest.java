package dev.otectus.mcacrime;

import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.mask.MaskCustomization;
import dev.otectus.mcacrime.mask.MaskHeatLedger;
import dev.otectus.mcacrime.mask.MaskNbtTransfer;
import dev.otectus.mcacrime.mask.MaskRestyleRejection;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.PlayerCrimeData.PendingMaskedHeat;
import net.minecraft.nbt.CompoundTag;
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

    private static final ResourceLocation CRIME = new ResourceLocation("mcacrime", "harm_villager");

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

    /**
     * MASK-12, and the structural reason it holds: a restyle cannot reach the ledger.
     *
     * <p>Deferred Heat, the pursuit clock and a witness's knowledge live on the player, never on the
     * stack. {@code MaskCustomization} takes two item stacks and returns a third; it is handed no
     * player, no ledger and no witness, so "changing style or colour after a witness identified the
     * wearer does not clear what they know" is not a discipline anybody has to remember -- there is no
     * expression in the customization path that could do it.
     *
     * <p>The test spends a whole restyle's worth of work between two reads of the ledger and asserts
     * both halves are byte-identical. If somebody ever gives the customization path a player, this is
     * the test that starts caring.
     */
    @Test
    void restylingAMaskLeavesDeferredHeatAndPursuitExactlyWhereTheyWere() {
        PlayerCrimeData player = new PlayerCrimeData();
        player.setOnlineTicksLived(5_000L);
        player.setMaskedPursuitUntilTick(6_400L);
        MaskHeatLedger.defer(player.getPendingMaskedHeat(), entry(25L, 4_000L), 32);
        MaskHeatLedger.defer(player.getPendingMaskedHeat(), entry(30L, 4_500L), 32);
        CompoundTag before = player.save();

        // The whole restyle, minus the stacks: the pairing decision and the item-history transfer.
        CompoundTag maskTag = new CompoundTag();
        maskTag.putInt("Damage", 12);
        CompoundTag mine = new CompoundTag();
        mine.putString("provenance", "incident-a");
        maskTag.put("mcacrime", mine);
        assertEquals(MaskRestyleRejection.NONE,
                MaskCustomization.decide(MaskVariant.CLAY, MaskVariant.TRAGEDY, maskTag, false));
        CompoundTag restyled = MaskNbtTransfer.merge(maskTag, null).orElseThrow();
        assertEquals("incident-a", restyled.getCompound("mcacrime").getString("provenance"));

        assertEquals(before, player.save(), "a restyle is not amnesty");
        assertEquals(55L, MaskHeatLedger.total(player.getPendingMaskedHeat()));
        assertEquals(6_400L, player.getMaskedPursuitUntilTick(), "the hunt for the masked figure goes on");
        assertTrue(player.isMaskedPursuit());
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
