package dev.otectus.mcacrime;

import dev.otectus.mcacrime.facility.CellReservation;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.facility.FacilityRole;
import dev.otectus.mcacrime.facility.TownsteadBuildingRef;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.7.3 step: schema 13 adds two collections and writes nothing.
 *
 * <p>The property under test is the one every additive migration has to hold — a schema-12 world loads
 * with the new collections empty and behaves exactly as it did — plus the one this step specifically
 * refuses. Seeding {@code facilities} from the existing jail roster would look generous and would be a
 * fabrication twice over: a facility asserts a village, a building id and the revision it was read at,
 * which no jail anchor knows, and a capacity that arrests are reserved against, which nobody assessed.
 */
class CrimeDataMigrationsV12toV13Test {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    /** A schema-12 store with a jail roster and a holding cell in it: the tempting seed material. */
    private static CompoundTag schema12() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_OCCUPATION);
        ListTag anchors = new ListTag();
        anchors.add(new JailAnchor(new BlockPos(10, 64, 10), OVERWORLD, 4).save());
        anchors.add(new JailAnchor(new BlockPos(-30, 70, 12), OVERWORLD, 6).save());
        tag.put("jailRoster", anchors);
        tag.put("ledger", new ListTag());
        return tag;
    }

    /**
     * Thirteen is still thirteen, and still follows twelve.
     *
     * <p>It is deliberately no longer asserted to be the <em>current</em> schema: 0.7.4 adds fourteen,
     * and the claim this file is responsible for is that its own step keeps its own number. Whichever
     * schema is current is {@code CrimeDataMigrationsV13toV14Test}'s business.
     */
    @Test
    void theFacilitySchemaIsThirteenAndFollowsTwelve() {
        assertEquals(13, CrimeDataMigrations.SCHEMA_TOWNSTEAD_FACILITIES);
        assertEquals(CrimeDataMigrations.SCHEMA_OCCUPATION + 1,
                CrimeDataMigrations.SCHEMA_TOWNSTEAD_FACILITIES,
                "schema 13 must follow 12 directly; a gap would make the version meaningless");
        assertTrue(CrimeDataMigrations.CURRENT_SCHEMA >= CrimeDataMigrations.SCHEMA_TOWNSTEAD_FACILITIES,
                "a later release may move past 13, but never back behind it");
    }

    @Test
    void migratingStampsThirteenAndWritesNothingElse() {
        CompoundTag before = schema12();

        CompoundTag after = CrimeDataMigrations.v12to13(before);

        assertEquals(CrimeDataMigrations.SCHEMA_TOWNSTEAD_FACILITIES,
                after.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertFalse(after.contains("facilities"),
                "absent already reads as empty; writing an empty list into every world grows the file "
                        + "to say nothing");
        assertFalse(after.contains("cellReservations"));
        assertEquals(before.getList("jailRoster", Tag.TAG_COMPOUND).size(),
                after.getList("jailRoster", Tag.TAG_COMPOUND).size(),
                "nothing else may be touched by an additive step");
    }

    @Test
    void noJailAnchorBecomesAFacility() {
        CrimeWorldData data = CrimeWorldData.load(schema12(), RegistryAccess.EMPTY);

        assertEquals(2, data.jailAnchors().size(), "the anchors themselves survive untouched");
        assertTrue(data.facilities().isEmpty(),
                "a facility asserts a building and a capacity no jail anchor ever knew; minting one "
                        + "would start routing arrests into a structure nobody assessed");
        assertTrue(data.cellReservations().isEmpty(),
                "a reservation is a lease held by a live escort, and no escort survives the restart "
                        + "that runs this migration");
    }

    @Test
    void aSchemaTwelveWorldLoadsAndSavesAtThirteen() {
        CrimeWorldData data = CrimeWorldData.load(schema12(), RegistryAccess.EMPTY);

        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);

        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, saved.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertTrue(saved.contains("facilities", Tag.TAG_LIST));
        assertEquals(0, saved.getList("facilities", Tag.TAG_COMPOUND).size());
        assertEquals(0, saved.getList("cellReservations", Tag.TAG_COMPOUND).size());
    }

    @Test
    void runningTheStepTwiceChangesNothingTheSecondTime() {
        CompoundTag once = CrimeDataMigrations.v12to13(schema12());
        CompoundTag twice = CrimeDataMigrations.v12to13(once);

        assertEquals(once, twice);
    }

    @Test
    void anUnversionedStoreStillClimbsAllTheWayToThirteen() {
        CompoundTag legacy = new CompoundTag();
        legacy.put("ledger", new ListTag());

        CompoundTag migrated = CrimeDataMigrations.migrate(legacy);

        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, migrated.getInt(CrimeDataMigrations.TAG_SCHEMA));
    }

    @Test
    void theNewCollectionsRoundTripWhenAWorldActuallyHasThem() {
        CrimeWorldData data = CrimeWorldData.load(schema12(), RegistryAccess.EMPTY);
        FacilityAssignment facility = new FacilityAssignment(UUID.randomUUID(),
                new TownsteadBuildingRef(OVERWORLD, 3, 7, 12), FacilityRole.JAIL_CELL,
                new BlockPos(5, 64, 5), 1, "operator", 100L);
        UUID prisoner = UUID.randomUUID();
        assertTrue(data.putFacility(facility));
        assertTrue(data.putCellReservation(new CellReservation(UUID.randomUUID(), facility.id(), 0,
                prisoner, 100L, 700L)));

        CrimeWorldData reloaded = CrimeWorldData.load(
                data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        assertEquals(List.of(facility), reloaded.facilities());
        CellReservation held = reloaded.cellReservationForPrisoner(prisoner, 200L);
        assertNotNull(held);
        assertEquals(facility.id(), held.facilityId());
    }

    @Test
    void aReservationWhoseFacilityDidNotSurviveIsDroppedRatherThanKept() {
        CrimeWorldData data = CrimeWorldData.load(schema12(), RegistryAccess.EMPTY);
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        ListTag reservations = new ListTag();
        reservations.add(new CellReservation(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID(),
                0L, 600L).save());
        saved.put("cellReservations", reservations);

        CrimeWorldData reloaded = CrimeWorldData.load(saved, RegistryAccess.EMPTY);

        assertTrue(reloaded.cellReservations().isEmpty(),
                "a lease on a slot in a facility that no longer exists can never be spent or released");
    }
}
