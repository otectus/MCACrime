package dev.otectus.mcacrime;

import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.PlayerCrimeData.PendingMaskedHeat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mask additions to the player capability round-trip, and the two halves of them part company on
 * death exactly as intended: the Heat a mask hid is carried over, the pursuit chasing it is not.
 *
 * <p>As with the 0.7.0 mugging fields, both keys are optional, so a save written before masks existed
 * loads as "never wore one" rather than as a corrupt tag. No migration.
 */
class PlayerCrimeDataMaskNbtTest {

    private static final ResourceLocation CRIME = ResourceLocation.fromNamespaceAndPath("mcacrime", "kill_villager");

    private static PlayerCrimeData masked() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.setOnlineTicksLived(1_000L);
        data.setMaskedPursuitUntilTick(2_200L);
        data.getPendingMaskedHeat().add(new PendingMaskedHeat(CRIME, "incident-a", 25L, 400L));
        data.getPendingMaskedHeat().add(new PendingMaskedHeat(CRIME, "incident-b", 30L, 900L));
        return data;
    }

    @Test
    void deferredHeatAndPursuitSurviveSaveAndLoad() {
        PlayerCrimeData loaded = new PlayerCrimeData();
        loaded.load(masked().save());
        assertEquals(2_200L, loaded.getMaskedPursuitUntilTick());
        assertEquals(2, loaded.getPendingMaskedHeat().size());
        PendingMaskedHeat first = loaded.getPendingMaskedHeat().get(0);
        assertEquals(CRIME, first.crimeId());
        assertEquals("incident-a", first.incidentId());
        assertEquals(25L, first.heat());
        assertEquals(400L, first.tick());
    }

    @Test
    void aTagWithNoMaskKeysLoadsEmpty() {
        PlayerCrimeData loaded = masked();
        loaded.load(new CompoundTag());
        assertEquals(0L, loaded.getMaskedPursuitUntilTick());
        assertTrue(loaded.getPendingMaskedHeat().isEmpty());
        assertFalse(loaded.isMaskedPursuit());
    }

    @Test
    void deathCarriesTheDebtButNotThePursuit() {
        PlayerCrimeData respawned = new PlayerCrimeData();
        respawned.copyFrom(masked());
        assertEquals(2, respawned.getPendingMaskedHeat().size(), "dying in a mask is not amnesty");
        assertEquals(0L, respawned.getMaskedPursuitUntilTick(), "but the guard has lost the figure they saw");
        assertFalse(respawned.isMaskedPursuit());
    }

    @Test
    void aCopyDoesNotShareTheLedgerWithItsSource() {
        PlayerCrimeData source = masked();
        PlayerCrimeData copy = new PlayerCrimeData();
        copy.copyFrom(source);
        copy.getPendingMaskedHeat().clear();
        assertEquals(2, source.getPendingMaskedHeat().size());
    }

    @Test
    void pursuitIsMeasuredOnThePlayersOwnOnlineClock() {
        PlayerCrimeData data = masked();
        assertTrue(data.isMaskedPursuit());
        data.setOnlineTicksLived(2_200L);
        assertFalse(data.isMaskedPursuit(), "the clock caught up with the pursuit");
    }
}
