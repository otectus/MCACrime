package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.7.5 step: schema 15 opens the four physical tables and gives every captivity an identity.
 *
 * <p>Unlike the last two steps this one writes, and the refusal that matters is still the tempting
 * one: a schema-14 record says a prisoner is in {@code CUFFS}, and turning that into a worn instance
 * here would be quick. It is also a decision that needs the definition registry, an item snapshot and
 * a provenance — none of which belong in a pure tag-to-tag function, and all of which have to happen
 * exactly once rather than on every load. That is
 * {@code restraint/RestraintMigrationReconciler}'s job, and this step leaves the gear alone.
 */
class CrimeDataMigrationsV14toV15Test {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final UUID PRISONER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** A schema-14 store with one cuffed, mid-escape kidnapping victim. */
    private static CompoundTag schema14() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_PROPERTY_LAW);
        tag.put("ledger", new ListTag());

        CustodyRecord record = new CustodyRecord(PRISONER, true, false,
                CustodyOwner.kidnapper(UUID.randomUUID()), 77L,
                new BlockPos(10, 64, 10), OVERWORLD);
        // What a schema-14 row said was on them. 0.7.5 constructs no record with one, so the field is
        // set the only way it can now be reached: as migration input.
        record.setLegacyRestraint(RestraintType.CUFFS);
        record.setEscapeActive(true);
        record.setEscapeProgress(120);
        record.setRealTicksHeld(900L);
        CompoundTag row = record.save();
        // A pre-0.7.5 row has neither field; the constructor above is a 0.7.5 class.
        row.remove("custodyId");
        row.remove("generation");

        CompoundTag custody = new CompoundTag();
        custody.put(PRISONER.toString(), row);
        tag.put("custody", custody);
        return tag;
    }

    @Test
    void theCurrentSchemaIsFifteenAndFollowsFourteenDirectly() {
        assertEquals(15, CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL);
        assertEquals(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL, CrimeDataMigrations.CURRENT_SCHEMA);
        assertEquals(CrimeDataMigrations.SCHEMA_PROPERTY_LAW + 1,
                CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL);
    }

    @Test
    void migratingStampsFifteenAndOpensTheFourTables() {
        CompoundTag after = CrimeDataMigrations.v14to15(schema14());

        assertEquals(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL,
                after.getInt(CrimeDataMigrations.TAG_SCHEMA));
        for (String key : new String[]{"physicalRestraints", "tethers", "detentions", "locks"}) {
            assertTrue(after.contains(key, Tag.TAG_LIST), key + " was not opened");
            assertEquals(0, after.getList(key, Tag.TAG_COMPOUND).size(),
                    key + " was seeded; migration invents no gear");
        }
    }

    @Test
    void everyCustodyRowGetsADerivedIdentityAndTheFirstGeneration() {
        CompoundTag after = CrimeDataMigrations.v14to15(schema14());

        CompoundTag row = after.getCompound("custody").getCompound(PRISONER.toString());
        assertEquals(CustodyRecord.legacyCustodyId(PRISONER), row.getUUID("custodyId"),
                "the id has to be derived, or the migration and a direct load disagree");
        assertEquals(1L, row.getLong("generation"));
    }

    @Test
    void anExistingIdentityIsNeverRewritten() {
        CompoundTag store = schema14();
        UUID theirs = UUID.randomUUID();
        store.getCompound("custody").getCompound(PRISONER.toString()).putUUID("custodyId", theirs);
        store.getCompound("custody").getCompound(PRISONER.toString()).putLong("generation", 4L);

        CompoundTag after = CrimeDataMigrations.v14to15(store);

        CompoundTag row = after.getCompound("custody").getCompound(PRISONER.toString());
        assertEquals(theirs, row.getUUID("custodyId"));
        assertEquals(4L, row.getLong("generation"), "a handover that already happened is not undone");
    }

    @Test
    void anInFlightTimedEscapeIsCancelled() {
        CompoundTag after = CrimeDataMigrations.v14to15(schema14());

        CompoundTag row = after.getCompound("custody").getCompound(PRISONER.toString());
        assertFalse(row.getBoolean("escapeActive"),
                "the engine that scored that attempt is being replaced; leaving the flag set would "
                        + "leave somebody mid-escape under a system with no record of it");
        assertEquals(0L, row.getLong("escapeCooldownUntil"), "and nobody is charged for the cancellation");
    }

    @Test
    void nothingElseAboutTheCustodyRowMoves() {
        CompoundTag before = schema14().getCompound("custody").getCompound(PRISONER.toString());
        CompoundTag after = CrimeDataMigrations.v14to15(schema14())
                .getCompound("custody").getCompound(PRISONER.toString());

        assertEquals(before.getString("restraint"), after.getString("restraint"),
                "the enum is read by the reconciler; the migration does not consume it");
        assertEquals(before.getLong("held"), after.getLong("held"));
        assertEquals(before.getLong("start"), after.getLong("start"));
        assertEquals(before.getInt("hx"), after.getInt("hx"));
        assertEquals(before.getString("hdim"), after.getString("hdim"));
    }

    @Test
    void runningTheStepTwiceChangesNothingTheSecondTime() {
        CompoundTag once = CrimeDataMigrations.v14to15(schema14());
        CompoundTag twice = CrimeDataMigrations.v14to15(once);

        assertEquals(once, twice);
    }

    @Test
    void aStoreWithNoCustodyAtAllMigratesCleanly() {
        CompoundTag bare = new CompoundTag();
        bare.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_PROPERTY_LAW);

        CompoundTag after = CrimeDataMigrations.v14to15(bare);

        assertEquals(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL,
                after.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertFalse(after.contains("custody"), "an absent table is not invented");
    }

    @Test
    void anUnversionedStoreStillClimbsAllTheWayToFifteen() {
        CompoundTag legacy = new CompoundTag();
        legacy.put("ledger", new ListTag());

        CompoundTag migrated = CrimeDataMigrations.migrate(legacy);

        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, migrated.getInt(CrimeDataMigrations.TAG_SCHEMA));
    }

    @Test
    void aSchemaFourteenWorldLoadsPlaysAndSavesAtFifteen() {
        CrimeWorldData data = CrimeWorldData.load(schema14(), RegistryAccess.EMPTY);

        assertFalse(data.isReadOnlyFutureData());
        assertFalse(data.isLoadFailed());
        CustodyRecord record = data.getCustody(PRISONER);
        assertEquals(CustodyRecord.legacyCustodyId(PRISONER), record.getCustodyId());
        assertEquals(RestraintType.CUFFS, record.getLegacyRestraint(),
                "the legal record is untouched by the schema step");
        assertEquals(900L, record.getRealTicksHeld());

        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, saved.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertTrue(saved.contains("physicalRestraints", Tag.TAG_LIST));
        assertEquals(0, saved.getList("physicalRestraints", Tag.TAG_COMPOUND).size(),
                "loading a schema-14 world does not by itself put gear on anybody");
    }
}
