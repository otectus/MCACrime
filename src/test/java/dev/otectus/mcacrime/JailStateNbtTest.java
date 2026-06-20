package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailContainmentMode;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jail persistence (spec §7.1): NBT round-trip, deep copy on death, absent-key safety, capability carry. */
class JailStateNbtTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");

    @Test
    void roundTripsAllFields() {
        JailState j = new JailState(1234L, new BlockPos(1, 2, 3), NETHER, 7, JailContainmentMode.PHYSICAL);
        j.setRealOnlineTicksServed(99L);
        j.setEscaped(true);

        JailState loaded = JailState.load(j.save());
        assertEquals(1234L, loaded.getRemainingOnlineTicks());
        assertEquals(99L, loaded.getRealOnlineTicksServed());
        assertEquals(new BlockPos(1, 2, 3), loaded.getJailAnchor());
        assertEquals(NETHER, loaded.getJailDim());
        assertEquals(7, loaded.getJailRadius());
        assertEquals(JailContainmentMode.PHYSICAL, loaded.getModeSnapshot());
        assertTrue(loaded.isEscaped());
        assertTrue(loaded.hasValidAnchor());
    }

    @Test
    void copyIsDeep() {
        JailState j = new JailState(100L, new BlockPos(0, 64, 0), OVERWORLD, 5, JailContainmentMode.CONTAINMENT);
        JailState c = j.copy();
        j.setRemainingOnlineTicks(50L);
        j.setEscaped(true);
        assertEquals(100L, c.getRemainingOnlineTicks()); // unaffected by mutating the original
        assertFalse(c.isEscaped());
    }

    @Test
    void absentKeysLoadSafeDefaults() {
        JailState s = JailState.load(new CompoundTag());
        assertEquals(0L, s.getRemainingOnlineTicks());
        assertEquals(0L, s.getRealOnlineTicksServed());
        assertNull(s.getJailAnchor());
        assertNull(s.getJailDim());
        assertEquals(JailContainmentMode.CONTAINMENT, s.getModeSnapshot());
        assertFalse(s.isEscaped());
        assertFalse(s.hasValidAnchor());
    }

    @Test
    void playerCrimeDataCarriesJailThroughSaveAndDeath() {
        PlayerCrimeData data = new PlayerCrimeData();
        data.setJail(new JailState(500L, new BlockPos(0, 64, 0), OVERWORLD, 5, JailContainmentMode.CONTAINMENT));

        // save -> load (logout/restart)
        PlayerCrimeData loaded = new PlayerCrimeData();
        loaded.load(data.save());
        assertTrue(loaded.isJailed());
        assertEquals(500L, loaded.getJail().getRemainingOnlineTicks());

        // copyFrom (death) keeps jail, and is a deep copy
        PlayerCrimeData respawn = new PlayerCrimeData();
        respawn.copyFrom(data);
        assertTrue(respawn.isJailed());
        data.getJail().setRemainingOnlineTicks(1L);
        assertEquals(500L, respawn.getJail().getRemainingOnlineTicks());
    }
}
