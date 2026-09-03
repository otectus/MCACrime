package dev.otectus.mcacrime;

import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resisting arrest, as persisted state.
 *
 * <p>It used to be a static map that the guard scan pruned for anybody who was not already a Legal
 * Target — that is, for exactly the players whose refusal was the only thing making force lawful. It
 * lives on the player now, which means it has to survive the three things a transient map never did:
 * a save/load round trip, a death, and running down on the player's own online clock rather than on
 * wall time.
 */
class ResistingArrestStateTest {

    private static PlayerCrimeData resisting(long onlineTicks, long until) {
        PlayerCrimeData data = new PlayerCrimeData();
        data.setOnlineTicksLived(onlineTicks);
        data.setResistingArrestUntilTick(until);
        return data;
    }

    @Test
    void aFreshPlayerIsNotResisting() {
        assertFalse(new PlayerCrimeData().isResistingArrest());
    }

    @Test
    void resistingUntilAFutureOnlineTickHolds() {
        assertTrue(resisting(100L, 2500L).isResistingArrest());
    }

    /** The boundary is exclusive: at exactly the deadline it has lapsed. */
    @Test
    void itLapsesExactlyAtItsDeadline() {
        assertTrue(resisting(2399L, 2400L).isResistingArrest());
        assertFalse(resisting(2400L, 2400L).isResistingArrest());
        assertFalse(resisting(2401L, 2400L).isResistingArrest());
    }

    @Test
    void clearingItSetsItToZeroRatherThanToTheCurrentTick() {
        PlayerCrimeData data = resisting(100L, 2500L);
        data.setResistingArrestUntilTick(0L);
        assertFalse(data.isResistingArrest());
        assertEquals(0L, data.getResistingArrestUntilTick());
    }

    @Test
    void aNegativeDeadlineIsClampedRatherThanStored() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.setResistingArrestUntilTick(-500L);
        assertEquals(0L, data.getResistingArrestUntilTick());
    }

    @Test
    void itSurvivesASaveAndLoad() {
        PlayerCrimeData saved = resisting(100L, 2500L);
        CompoundTag tag = saved.save();
        PlayerCrimeData loaded = new PlayerCrimeData();
        loaded.load(tag);
        assertEquals(2500L, loaded.getResistingArrestUntilTick());
        assertTrue(loaded.isResistingArrest());
    }

    /** Dying to the guard you refused is not compliance, so {@code copyFrom} has to carry it across. */
    @Test
    void itSurvivesDeath() {
        PlayerCrimeData respawned = new PlayerCrimeData();
        respawned.copyFrom(resisting(100L, 2500L));
        assertTrue(respawned.isResistingArrest());
    }

    /** Pre-0.4.0 saves have no such key, and an absent key must read as "not resisting". */
    @Test
    void anOlderSaveWithoutTheKeyLoadsAsCompliant() {
        PlayerCrimeData loaded = new PlayerCrimeData();
        loaded.load(new CompoundTag());
        assertFalse(loaded.isResistingArrest());
        assertEquals(0L, loaded.getResistingArrestUntilTick());
    }
}
