package dev.otectus.mcacrime.facility;

import dev.otectus.mcacrime.compat.TownsteadBuildingView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing a stored building reference has to be able to say: has this village been restructured
 * since I last looked?
 *
 * <p>Between two sessions a village can merge, split, be deleted, or reuse a building id for something
 * else. A reference that could only say "village 3, building 7" would happily re-attach a sentence to
 * whatever now holds that id — which is how a jail becomes somebody's kitchen. The revision is what
 * makes the question answerable, and the third state (<em>uncomparable</em>) is what stops the answer
 * being a guess when no revision was ever taken.
 */
class TownsteadBuildingRefTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    private static TownsteadBuildingView building(int id, int villageId, int revision) {
        return new TownsteadBuildingView(id, villageId, "kitchen_l1", 0, 5, 64, 5,
                0, 60, 0, 10, 68, 10, "building", revision);
    }

    @Test
    void aChangedRevisionIsStale() {
        TownsteadBuildingRef ref = new TownsteadBuildingRef(OVERWORLD, 3, 7, 12);

        assertFalse(ref.isStale(12), "the same revision is the whole point of storing one");
        assertTrue(ref.isStale(13), "a village that has been restructured must invalidate the reference");
        assertTrue(ref.comparable());
    }

    @Test
    void aReferenceWithNoObservedRevisionIsUncomparableRatherThanFresh() {
        TownsteadBuildingRef ref = new TownsteadBuildingRef(OVERWORLD, 3, 7,
                TownsteadBuildingRef.UNKNOWN_REVISION);

        assertFalse(ref.comparable(),
                "a reference taken with no revision cannot be compared, and must not claim it can");
        assertFalse(ref.isStale(99),
                "reporting it stale would invalidate every facility on a server with no settlement mod");
    }

    @Test
    void anUnboundReferenceNamesNoBuilding() {
        TownsteadBuildingRef ref = TownsteadBuildingRef.unbound(OVERWORLD);

        assertFalse(ref.bound());
        assertFalse(ref.comparable());
        assertFalse(ref.names(building(7, 3, 12)));
        assertEquals(OVERWORLD, ref.dimension());
    }

    @Test
    void itNamesOnlyItsOwnVillageAndBuilding() {
        TownsteadBuildingRef ref = TownsteadBuildingRef.of(OVERWORLD, building(7, 3, 12));

        assertTrue(ref.names(building(7, 3, 12)));
        assertFalse(ref.names(building(8, 3, 12)), "a different building id is a different building");
        assertFalse(ref.names(building(7, 4, 12)), "the same id in another village is another building");
        assertFalse(ref.names(null));
    }

    @Test
    void revalidationRestampsTheRevision() {
        TownsteadBuildingRef ref = new TownsteadBuildingRef(OVERWORLD, 3, 7, 12);

        TownsteadBuildingRef restamped = ref.observedAt(13);

        assertEquals(13, restamped.observedRevision());
        assertFalse(restamped.isStale(13));
        assertEquals(12, ref.observedRevision(), "the original is immutable");
    }

    @Test
    void itRoundTripsThroughNbt() {
        TownsteadBuildingRef ref = new TownsteadBuildingRef(NETHER, 3, 7, 12);

        TownsteadBuildingRef loaded = TownsteadBuildingRef.load(ref.save());

        assertNotNull(loaded);
        assertEquals(ref, loaded);
    }

    @Test
    void aMalformedDimensionLoadsAsNothingRatherThanADefault() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", "not a dimension id");
        tag.putInt("village", 3);
        tag.putInt("building", 7);

        assertNull(TownsteadBuildingRef.load(tag),
                "a reference whose dimension cannot be read must not be repaired into the overworld");
    }

    @Test
    void anAbsentRevisionLoadsAsUnknownRatherThanZero() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", OVERWORLD.toString());
        tag.putInt("village", 3);
        tag.putInt("building", 7);

        TownsteadBuildingRef loaded = TownsteadBuildingRef.load(tag);

        assertNotNull(loaded);
        assertEquals(TownsteadBuildingRef.UNKNOWN_REVISION, loaded.observedRevision(),
                "revision 0 is a real value -- a village record that exists and has never had a building "
                        + "stored -- so an absent one must not read as it");
    }
}
