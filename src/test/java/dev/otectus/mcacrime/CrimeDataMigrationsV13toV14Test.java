package dev.otectus.mcacrime;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.civic.CivicTask;
import dev.otectus.mcacrime.civic.ServiceContract;
import dev.otectus.mcacrime.facility.FacilityAssignment;
import dev.otectus.mcacrime.facility.FacilityRole;
import dev.otectus.mcacrime.facility.TownsteadBuildingRef;
import dev.otectus.mcacrime.property.PropertyAccessRule;
import dev.otectus.mcacrime.property.PropertyOwnerKind;
import dev.otectus.mcacrime.property.PropertyPolicy;
import dev.otectus.mcacrime.property.PropertySource;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.7.4 step: schema 14 adds two collections and writes nothing.
 *
 * <p>The refusal that matters here is the tempting one. A schema-13 world already records which
 * buildings are evidence storage and which are cells — exactly the containers §10.1 wants protected —
 * so minting a property policy for each would hand every upgrading world a working property layer for
 * free. It would also make a claim nobody made, under a law that ships off, retroactively, at
 * containers players have been using since before this release existed. The automatic sweep writes
 * those policies on a server whose operator asked for them, and nowhere else.
 */
class CrimeDataMigrationsV13toV14Test {

    private static final ResourceLocation OVERWORLD =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    /** A schema-13 store with the seed material: an evidence store and a cell, both assigned. */
    private static CompoundTag schema13() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_TOWNSTEAD_FACILITIES);
        tag.put("ledger", new ListTag());
        ListTag facilities = new ListTag();
        facilities.add(new FacilityAssignment(UUID.randomUUID(),
                new TownsteadBuildingRef(OVERWORLD, 3, 7, 12), FacilityRole.EVIDENCE_STORAGE,
                new BlockPos(10, 64, 10), 0, "operator", 100L).save());
        facilities.add(new FacilityAssignment(UUID.randomUUID(),
                new TownsteadBuildingRef(OVERWORLD, 3, 8, 12), FacilityRole.JAIL_CELL,
                new BlockPos(20, 64, 20), 1, "operator", 100L).save());
        tag.put("facilities", facilities);
        return tag;
    }

    @Test
    void theCurrentSchemaIsFourteenAndIsIntroducedOnce() {
        assertEquals(14, CrimeDataMigrations.SCHEMA_PROPERTY_LAW);
        assertEquals(CrimeDataMigrations.SCHEMA_PROPERTY_LAW, CrimeDataMigrations.CURRENT_SCHEMA);
        assertEquals(CrimeDataMigrations.SCHEMA_TOWNSTEAD_FACILITIES + 1,
                CrimeDataMigrations.SCHEMA_PROPERTY_LAW,
                "schema 14 must follow 13 directly; a gap would make the version meaningless");
    }

    @Test
    void migratingStampsFourteenAndWritesNothingElse() {
        CompoundTag before = schema13();

        CompoundTag after = CrimeDataMigrations.v13to14(before);

        assertEquals(CrimeDataMigrations.SCHEMA_PROPERTY_LAW,
                after.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertFalse(after.contains("propertyPolicies"),
                "absent already reads as empty; writing an empty list into every world grows the file "
                        + "to say nothing");
        assertFalse(after.contains("propertyReceipts"));
        assertFalse(after.contains("serviceContracts"),
                "nobody agreed to work off a case in a world that had no way to offer it");
        assertEquals(before.getList("facilities", Tag.TAG_COMPOUND).size(),
                after.getList("facilities", Tag.TAG_COMPOUND).size(),
                "nothing else may be touched by an additive step");
    }

    @Test
    void noFacilityBecomesAPropertyPolicy() {
        CrimeWorldData data = CrimeWorldData.load(schema13(), RegistryAccess.EMPTY);

        assertEquals(2, data.facilities().size(), "the assignments themselves survive untouched");
        assertTrue(data.propertyPolicies().isEmpty(),
                "a policy decides whether taking from a container is a crime with a named victim; "
                        + "migration must not make that claim on an operator's behalf");
        assertTrue(data.propertyReceipts().isEmpty(),
                "a receipt records a loss under a law that did not exist in a schema-13 world");
    }

    @Test
    void serviceContractsReadTheirAbsenceAsEmptyAndRoundTripWhenPresent() {
        CrimeWorldData data = CrimeWorldData.load(schema13(), RegistryAccess.EMPTY);
        assertTrue(data.serviceContracts().isEmpty());
        assertNull(data.openServiceContractFor(UUID.randomUUID()));

        UUID offender = UUID.randomUUID();
        ServiceContract contract = ServiceContract.offered(UUID.randomUUID(), UUID.randomUUID(),
                offender, true, new CrimeCommunityKey(OVERWORLD, 3), CivicTask.GUARD_ASSIST_PATROL,
                2, 100L, 24_100L, null);
        assertTrue(data.putServiceContract(contract));

        CrimeWorldData reloaded = CrimeWorldData.load(
                data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        assertEquals(List.of(contract), reloaded.serviceContracts());
        assertEquals(contract, reloaded.openServiceContractFor(offender),
                "an obligation has to survive the reconnect, or the contract regenerates and reference "
                        + "§12.1's anti-farming rule is lost");
    }

    @Test
    void anUnreadableContractRowIsQuarantinedRatherThanDropped() {
        CompoundTag tag = schema13();
        ListTag contracts = new ListTag();
        CompoundTag broken = new CompoundTag();
        broken.putUUID("id", UUID.randomUUID());
        broken.putUUID("case", UUID.randomUUID());
        broken.putUUID("offender", UUID.randomUUID());
        broken.putString("task", "sweep_the_square"); // no such task
        broken.putString("state", "active");
        contracts.add(broken);
        tag.put("serviceContracts", contracts);

        CrimeWorldData data = CrimeWorldData.load(tag, RegistryAccess.EMPTY);

        assertTrue(data.serviceContracts().isEmpty());
        assertEquals(1, data.quarantineCount(),
                "an unreadable contract is still the reason somebody was not charged a fine");
    }

    @Test
    void aSchemaThirteenWorldLoadsAndSavesAtFourteen() {
        CrimeWorldData data = CrimeWorldData.load(schema13(), RegistryAccess.EMPTY);

        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);

        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, saved.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertTrue(saved.contains("propertyPolicies", Tag.TAG_LIST));
        assertEquals(0, saved.getList("propertyPolicies", Tag.TAG_COMPOUND).size());
        assertEquals(0, saved.getList("propertyReceipts", Tag.TAG_COMPOUND).size());
    }

    @Test
    void runningTheStepTwiceChangesNothingTheSecondTime() {
        CompoundTag once = CrimeDataMigrations.v13to14(schema13());
        CompoundTag twice = CrimeDataMigrations.v13to14(once);

        assertEquals(once, twice);
    }

    @Test
    void anUnversionedStoreStillClimbsAllTheWayToFourteen() {
        CompoundTag legacy = new CompoundTag();
        legacy.put("ledger", new ListTag());

        CompoundTag migrated = CrimeDataMigrations.migrate(legacy);

        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, migrated.getInt(CrimeDataMigrations.TAG_SCHEMA));
    }

    @Test
    void thePropertyCollectionsRoundTripWhenAWorldActuallyHasThem() {
        CrimeWorldData data = CrimeWorldData.load(schema13(), RegistryAccess.EMPTY);
        PropertyPolicy policy = PropertyPolicy.container(OVERWORLD, new BlockPos(10, 64, 10),
                new TownsteadBuildingRef(OVERWORLD, 3, 7, 12), PropertyOwnerKind.VILLAGE, null,
                PropertyAccessRule.RESIDENTS, true, PropertySource.MANUAL, "operator", 100L);
        assertTrue(data.putPropertyPolicy(policy));

        CrimeWorldData reloaded = CrimeWorldData.load(
                data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        assertEquals(List.of(policy), reloaded.propertyPolicies());
        assertNotNull(reloaded.propertyPolicyAt(OVERWORLD, new BlockPos(10, 64, 10)),
                "the container index has to be rebuilt on load, or the sourcing hook protects nothing");
    }

    @Test
    void anUnreadablePolicyRowIsQuarantinedRatherThanDropped() {
        CompoundTag tag = schema13();
        ListTag policies = new ListTag();
        CompoundTag broken = new CompoundTag();
        broken.putUUID("id", UUID.randomUUID());
        broken.putString("dim", "minecraft:overworld");
        broken.putString("scope", "container"); // no position: unreadable
        broken.putString("rule", "residents");
        broken.putString("ownerKind", "village");
        broken.putString("source", "manual");
        policies.add(broken);
        tag.put("propertyPolicies", policies);

        CrimeWorldData data = CrimeWorldData.load(tag, RegistryAccess.EMPTY);

        assertTrue(data.propertyPolicies().isEmpty());
        assertEquals(1, data.quarantineCount(),
                "a policy nobody can parse is still the reason a container was protected");
    }
}
