package dev.otectus.mcacrime;

import dev.otectus.mcacrime.fixtures.Schema7Fixture;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The port's two 0.7.8 steps, 15 to 16 and 16 to 17, both stamps and nothing else.
 *
 * <p>16 is the Forge build's village-justice schema, taken here as a stamp only so both trees agree on
 * what 16 and 17 mean; 17 adds the {@code decrees} table, which is absent in every older world and absent
 * reads as empty. What is pinned: neither step writes anything but the version, and nothing in an
 * existing world is turned into a decree -- a decree is a ruler's act after the feature exists.
 */
class CrimeDataMigrationsV15toV17Test {

    private static CompoundTag schema15() {
        CompoundTag tag = CrimeDataMigrations.migrate(Schema7Fixture.store());
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL);
        tag.remove("decrees");
        return tag;
    }

    @Test
    void sixteenAndSeventeenFollowFifteenAsOnForge() {
        assertEquals(16, CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE);
        assertEquals(17, CrimeDataMigrations.SCHEMA_DECREES);
        assertEquals(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL + 1, CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE);
        assertEquals(CrimeDataMigrations.SCHEMA_DECREES, CrimeDataMigrations.CURRENT_SCHEMA);
    }

    @Test
    void bothStepsStampTheirVersionAndChangeNothingElse() {
        CompoundTag before = schema15();
        CompoundTag sixteen = CrimeDataMigrations.v15to16(before);
        assertEquals(CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE, sixteen.getInt(CrimeDataMigrations.TAG_SCHEMA));
        CompoundTag seventeen = CrimeDataMigrations.v16to17(sixteen);
        assertEquals(CrimeDataMigrations.SCHEMA_DECREES, seventeen.getInt(CrimeDataMigrations.TAG_SCHEMA));
        CompoundTag withoutStamp = seventeen.copy();
        withoutStamp.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL);
        assertEquals(before, withoutStamp, "a step wrote something besides the version");
        assertFalse(seventeen.contains("decrees"), "a decree was invented on upgrade");
        assertEquals(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL, before.getInt(CrimeDataMigrations.TAG_SCHEMA),
                "the input tag was mutated in place");
    }

    @Test
    void aSchemaFifteenWorldLoadsWithNoDecreesAndWritesTheTableBack() {
        CrimeWorldData data = CrimeWorldData.load(schema15(), RegistryAccess.EMPTY);
        assertFalse(data.isReadOnlyFutureData());
        assertTrue(data.decrees().isEmpty());
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, saved.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertTrue(saved.contains("decrees", Tag.TAG_LIST));
        assertEquals(0, saved.getList("decrees", Tag.TAG_COMPOUND).size());
    }

    @Test
    void aWorldFromALaterSchemaIsHeldReadOnly() {
        CompoundTag future = schema15();
        future.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        assertTrue(CrimeWorldData.load(future, RegistryAccess.EMPTY).isReadOnlyFutureData());
    }
}
