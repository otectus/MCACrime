package dev.otectus.mcacrime;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dimension-aware community identity — the type that stops two villages with the same MCA id from
 * silently sharing one criminal record.
 */
class CrimeCommunityKeyTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    /**
     * The reason the whole type exists. MCA allocates village ids from a per-level store, so village 3
     * in the Nether and village 3 in the overworld are unrelated places.
     */
    @Test
    void sameVillageIdInDifferentDimensionsIsADifferentCommunity() {
        CrimeCommunityKey overworld = new CrimeCommunityKey(OVERWORLD, 3);
        CrimeCommunityKey nether = new CrimeCommunityKey(NETHER, 3);

        assertNotEquals(overworld, nether);

        // And they must stay distinct as map keys, which is how the standing store uses them.
        Map<CrimeCommunityKey, String> store = new HashMap<>();
        store.put(overworld, "overworld village");
        store.put(nether, "nether village");
        assertEquals(2, store.size());
        assertEquals("nether village", store.get(nether));
    }

    @Test
    void roundTripsThroughNbt() {
        CrimeCommunityKey key = new CrimeCommunityKey(NETHER, 42);
        assertEquals(Optional.of(key), CrimeCommunityKey.load(key.save()));
    }

    @Test
    void roundTripsThroughTheStringForm() {
        CrimeCommunityKey key = new CrimeCommunityKey(OVERWORLD, 7);
        assertEquals("minecraft:overworld/7", key.asString());
        assertEquals(Optional.of(key), CrimeCommunityKey.tryParse(key.asString()));
    }

    /**
     * Matching MCA: Reputation's contract exactly is what makes the bridge's conversion total — a key
     * that exists here can never blow up when the outbox hands it across, on a crime already committed.
     */
    @Test
    void aNegativeVillageIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CrimeCommunityKey(OVERWORLD, -1));
        assertTrue(CrimeCommunityKey.of(OVERWORLD, -1).isEmpty());
    }

    @Test
    void aNullDimensionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CrimeCommunityKey(null, 1));
        assertTrue(CrimeCommunityKey.of((ResourceLocation) null, 1).isEmpty());
    }

    /** Guarded factories return empty for nonsense so hand-edited NBT cannot fail a world load. */
    @Test
    void malformedInputParsesToEmptyRatherThanThrowing() {
        assertTrue(CrimeCommunityKey.tryParse(null).isEmpty());
        assertTrue(CrimeCommunityKey.tryParse("").isEmpty());
        assertTrue(CrimeCommunityKey.tryParse("minecraft:overworld").isEmpty());
        assertTrue(CrimeCommunityKey.tryParse("minecraft:overworld/").isEmpty());
        assertTrue(CrimeCommunityKey.tryParse("/3").isEmpty());
        assertTrue(CrimeCommunityKey.tryParse("minecraft:overworld/notanumber").isEmpty());
        assertTrue(CrimeCommunityKey.tryParse("NOT A RESOURCE LOCATION/3").isEmpty());
        assertTrue(CrimeCommunityKey.load(new CompoundTag()).isEmpty());
    }

    /**
     * Ordering only has to be <em>total and stable</em>, not alphabetical in any intuitive sense.
     * Note that vanilla's {@code ResourceLocation.compareTo} compares path before namespace, so
     * {@code minecraft:overworld} sorts ahead of {@code minecraft:the_nether} on "overworld" vs
     * "the_nether" rather than on the shared namespace. The property that matters is that the same
     * pair always compares the same way, so a case list never reorders between saves.
     */
    @Test
    void orderingIsTotalAndStable() {
        CrimeCommunityKey overworld3 = new CrimeCommunityKey(OVERWORLD, 3);
        CrimeCommunityKey overworld7 = new CrimeCommunityKey(OVERWORLD, 7);
        CrimeCommunityKey nether3 = new CrimeCommunityKey(NETHER, 3);

        assertEquals(0, overworld3.compareTo(new CrimeCommunityKey(OVERWORLD, 3)));
        assertTrue(overworld3.compareTo(overworld7) < 0, "same dimension orders by village id");

        int dimensionOrder = overworld3.compareTo(nether3);
        assertNotEquals(0, dimensionOrder, "different dimensions must never compare equal");
        assertEquals(dimensionOrder < 0, nether3.compareTo(overworld3) > 0, "ordering must be antisymmetric");
        assertEquals(dimensionOrder, new CrimeCommunityKey(OVERWORLD, 3).compareTo(nether3),
                "and it must not change between calls");
    }

    // ------------------------------------------------------------------ resolver

    @Test
    void theResolverCombinesDimensionAndVillageId() {
        assertEquals(Optional.of(new CrimeCommunityKey(NETHER, 5)),
                CrimeCommunityResolver.resolve(NETHER, OptionalInt.of(5)));
    }

    /** A crime outside any known village has no community, which is a valid outcome and not an error. */
    @Test
    void noVillageMeansNoCommunity() {
        assertTrue(CrimeCommunityResolver.resolve(OVERWORLD, OptionalInt.empty()).isEmpty());
        assertTrue(CrimeCommunityResolver.resolve(null, OptionalInt.of(3)).isEmpty());
    }
}
