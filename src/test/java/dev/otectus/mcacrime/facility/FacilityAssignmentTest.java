package dev.otectus.mcacrime.facility;

import dev.otectus.mcacrime.compat.TownsteadBuildingView;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an assignment is, what it survives, and what it refuses to become.
 *
 * <p>Two properties carry the weight. An assignment that cannot be read back is dropped rather than
 * repaired — a facility with a guessed role or a guessed dimension would route arrests somewhere
 * nobody chose. And validation distinguishes "not checked" from "gone", because an unloaded chunk is
 * not proof that a building was demolished (§8.7).
 */
class FacilityAssignmentTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private static TownsteadBuildingView building(int id, int villageId, int revision) {
        return new TownsteadBuildingView(id, villageId, "guardhouse_l1", 0, 5, 64, 5,
                0, 60, 0, 10, 68, 10, "building", revision);
    }

    private static FacilityAssignment assignment(int revision) {
        return FacilityAssignment.of(new TownsteadBuildingRef(OVERWORLD, 3, 7, revision),
                FacilityRole.JAIL_CELL, new BlockPos(5, 64, 5), "operator", 1000L);
    }

    @Test
    void aRoleCarriesItsOwnDefaultCapacity() {
        assertTrue(assignment(12).holdsPrisoners());
        assertEquals(1, assignment(12).capacity());

        FacilityAssignment post = FacilityAssignment.of(TownsteadBuildingRef.unbound(OVERWORLD),
                FacilityRole.GUARD_POST, BlockPos.ZERO, "operator", 0L);
        assertFalse(post.holdsPrisoners(),
                "a guard post is a patrol anchor; reserving a prisoner into one would be nonsense");
    }

    @Test
    void aGuardhouseAggregatesCellsRatherThanBecomingOne() {
        assertEquals(0, FacilityRole.GUARDHOUSE.defaultCapacity(),
                "treating every room of a building as a cell is how a prisoner ends up in the pantry");
        assertFalse(FacilityRole.GUARDHOUSE.holdsPrisoners());
        assertTrue(FacilityRole.JAIL_CELL.holdsPrisoners());
        assertTrue(FacilityRole.CARE_ROOM.providesCare());
    }

    @Test
    void everyRoleParsesByIdAndByName() {
        for (FacilityRole role : FacilityRole.values()) {
            assertEquals(Optional.of(role), FacilityRole.parse(role.id()));
            assertEquals(Optional.of(role), FacilityRole.parse(role.name()));
            assertEquals(Optional.of(role), FacilityRole.parse(role.id().toUpperCase(java.util.Locale.ROOT)));
        }
        assertEquals(Optional.empty(), FacilityRole.parse("kitchen"),
                "an unknown role must not resolve to a default; it would assign the wrong job silently");
        assertEquals(Optional.empty(), FacilityRole.parse(null));
    }

    @Test
    void itRoundTripsThroughNbt() {
        FacilityAssignment facility = assignment(12);

        FacilityAssignment loaded = FacilityAssignment.load(facility.save());

        assertNotNull(loaded);
        assertEquals(facility, loaded);
    }

    @Test
    void anUnreadableRowLoadsAsNothingRatherThanARepairedFacility() {
        CompoundTag missingRole = assignment(12).save();
        missingRole.putString("role", "dungeon");
        assertNull(FacilityAssignment.load(missingRole),
                "a facility with a guessed role would start taking arrests nobody routed to it");

        CompoundTag missingDimension = assignment(12).save();
        missingDimension.getCompound("ref").putString("dim", "not an id");
        assertNull(FacilityAssignment.load(missingDimension));

        assertNull(FacilityAssignment.load(new CompoundTag()));
        assertNull(FacilityAssignment.load(null));
    }

    @Test
    void anAssignmentMustNameARoleAnAnchorAndABuilding() {
        assertThrows(IllegalArgumentException.class, () -> new FacilityAssignment(UUID.randomUUID(),
                null, FacilityRole.JAIL_CELL, BlockPos.ZERO, 1, "operator", 0L));
        assertThrows(IllegalArgumentException.class, () -> new FacilityAssignment(UUID.randomUUID(),
                TownsteadBuildingRef.unbound(OVERWORLD), null, BlockPos.ZERO, 1, "operator", 0L));
        assertThrows(IllegalArgumentException.class, () -> new FacilityAssignment(UUID.randomUUID(),
                TownsteadBuildingRef.unbound(OVERWORLD), FacilityRole.JAIL_CELL, null, 1, "operator", 0L));
    }

    @Test
    void anUnchangedBuildingValidates() {
        FacilityAssignment facility = assignment(12);

        CrimeFacilityService.Validation validation = CrimeFacilityService.validate(facility.ref(),
                List.of(building(7, 3, 12)), true, true);

        assertEquals(CrimeFacilityService.Status.VALID, validation.status());
        assertEquals(12, validation.currentRevision());
        assertTrue(validation.usable());
    }

    @Test
    void aRestructuredVillageIsStaleAndNotUsable() {
        CrimeFacilityService.Validation validation = CrimeFacilityService.validate(
                assignment(12).ref(), List.of(building(7, 3, 13)), true, true);

        assertEquals(CrimeFacilityService.Status.STALE, validation.status());
        assertFalse(validation.usable(),
                "a stale facility is a demand that somebody look, not a destination for an arrest");
        assertTrue(validation.reason().contains("12"));
        assertTrue(validation.reason().contains("13"));
    }

    @Test
    void aBuildingThatIsGoneIsMissing() {
        CrimeFacilityService.Validation validation = CrimeFacilityService.validate(
                assignment(12).ref(), List.of(building(9, 3, 12)), true, true);

        assertEquals(CrimeFacilityService.Status.MISSING, validation.status());
        assertFalse(validation.usable());
    }

    @Test
    void anUnloadedChunkIsNeverProofOfDestruction() {
        CrimeFacilityService.Validation validation =
                CrimeFacilityService.validate(assignment(12).ref(), null, true, false);

        assertEquals(CrimeFacilityService.Status.UNVERIFIED, validation.status(),
                "§8.7: lack of a loaded chunk is not proof that a jail was demolished");
        assertTrue(validation.usable(), "an unverified facility must stay usable, or every sleeping "
                + "village would lose its jail");
    }

    @Test
    void withoutEnumerationEverythingIsUnverifiedRatherThanMissing() {
        CrimeFacilityService.Validation validation =
                CrimeFacilityService.validate(assignment(12).ref(), List.of(), false, true);

        assertEquals(CrimeFacilityService.Status.UNVERIFIED, validation.status());
        assertTrue(validation.usable(),
                "a server with no settlement mod must still be able to run a jail");
    }

    @Test
    void anUnboundReferenceIsUnverifiedRatherThanMissing() {
        FacilityAssignment facility = FacilityAssignment.of(TownsteadBuildingRef.unbound(OVERWORLD),
                FacilityRole.JAIL_CELL, BlockPos.ZERO, "operator", 0L);

        CrimeFacilityService.Validation validation =
                CrimeFacilityService.validate(facility.ref(), List.of(), true, true);

        assertEquals(CrimeFacilityService.Status.UNVERIFIED, validation.status());
    }

    @Test
    void revalidationRestampsTheAssignmentWithoutChangingItsIdentity() {
        FacilityAssignment facility = assignment(12);

        FacilityAssignment restamped = facility.revalidatedAt(13);

        assertEquals(facility.id(), restamped.id());
        assertEquals(13, restamped.ref().observedRevision());
        assertSame(facility, facility.revalidatedAt(12), "an unchanged revision changes nothing");
    }
}
