package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Jail anchor persistence (spec §7.4): anchor NBT round-trip and the CrimeWorldData registry. */
class JailRegistryNbtTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");

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

        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag()));
        assertEquals(2, loaded.jailAnchors().size());
        assertEquals(new BlockPos(1, 2, 3), loaded.jailAnchors().get(0).pos());
        assertEquals(NETHER, loaded.jailAnchors().get(1).dim());
    }
}
