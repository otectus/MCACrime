package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailRegistry;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jail anchor persistence (spec §7.4): anchor NBT round-trip and the CrimeWorldData registry. */
class JailRegistryNbtTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    @Test
    void anchorRoundTrips() {
        JailAnchor a = new JailAnchor(new BlockPos(10, 64, -5), OVERWORLD, 8);
        assertEquals(a, JailAnchor.load(a.save()));
    }

    @Test
    void malformedDimYieldsNull() {
        CompoundTag bad = new CompoundTag();
        bad.putInt("x", 1);
        bad.putInt("y", 2);
        bad.putInt("z", 3);
        bad.putString("dim", "not a valid dim!!");
        bad.putInt("radius", 4);
        assertNull(JailAnchor.load(bad));
    }

    @Test
    void worldDataAnchorsRoundTrip() {
        CrimeWorldData data = new CrimeWorldData();
        data.addJailAnchor(new JailAnchor(new BlockPos(1, 2, 3), OVERWORLD, 4));
        data.addJailAnchor(new JailAnchor(new BlockPos(5, 6, 7), NETHER, 8));

        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);
        assertEquals(2, loaded.jailAnchors().size());
        assertEquals(new BlockPos(1, 2, 3), loaded.jailAnchors().get(0).pos());
        assertEquals(NETHER, loaded.jailAnchors().get(1).dim());
    }

    // ---------------------------------------------------------------- the distance ceiling

    /**
     * Without a ceiling a single {@code /crime assignjail} anywhere in a dimension became the
     * destination for every arrest in it, teleporting prisoners across the map and permanently
     * suppressing holding-cell construction, because the assigned anchor always won the priority ladder.
     */
    @Test
    void aCeilingOfZeroMeansNoCeilingAtAll() {
        assertTrue(JailRegistry.withinCeiling(1.0E9, 0.0));
        assertTrue(JailRegistry.withinCeiling(1.0E9, -1.0));
    }

    @Test
    void theCeilingComparesSquaredDistanceAgainstASquaredLimit() {
        assertTrue(JailRegistry.withinCeiling(0.0, 3.0));
        assertTrue(JailRegistry.withinCeiling(9.0, 3.0), "exactly at the limit is inside it");
        assertFalse(JailRegistry.withinCeiling(9.01, 3.0));
        assertFalse(JailRegistry.withinCeiling(65_536.0, 256.0 - 1.0));
        assertTrue(JailRegistry.withinCeiling(65_536.0, 256.0));
    }
}
