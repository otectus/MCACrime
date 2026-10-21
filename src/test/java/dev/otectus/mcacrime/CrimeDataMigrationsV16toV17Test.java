package dev.otectus.mcacrime;

import dev.otectus.mcacrime.fixtures.Schema7Fixture;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.7.8 royal-decree step: schema 17 adds the {@code decrees} table and writes nothing else.
 *
 * <p>The refusal worth pinning: nothing in an existing world is a decree. A decree is a ruler's act after
 * the feature exists; seeding the table from cases, warrants or Capitals' own justice would fabricate
 * orders nobody gave.
 */
class CrimeDataMigrationsV16toV17Test {

    private static CompoundTag schema16() {
        CompoundTag tag = CrimeDataMigrations.migrate(Schema7Fixture.store());
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE);
        tag.remove("decrees");
        return tag;
    }

    @Test
    void decreesAreSeventeenAndFollowSixteenDirectly() {
        assertEquals(17, CrimeDataMigrations.SCHEMA_DECREES);
        assertEquals(CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE + 1, CrimeDataMigrations.SCHEMA_DECREES);
        assertTrue(CrimeDataMigrations.CURRENT_SCHEMA >= CrimeDataMigrations.SCHEMA_DECREES);
    }

    @Test
    void theStepStampsSeventeenAndChangesNothingElse() {
        CompoundTag before = schema16();
        CompoundTag after = CrimeDataMigrations.v16to17(before);
        assertEquals(CrimeDataMigrations.SCHEMA_DECREES, after.getInt(CrimeDataMigrations.TAG_SCHEMA));
        CompoundTag withoutStamp = after.copy();
        withoutStamp.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE);
        assertEquals(before, withoutStamp, "the step wrote something besides the version");
        assertFalse(after.contains("decrees"), "a decree was invented on upgrade");
        assertEquals(CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE, before.getInt(CrimeDataMigrations.TAG_SCHEMA),
                "the input tag was mutated in place");
    }

    @Test
    void aSchemaSixteenWorldLoadsWithNoDecreesAndWritesTheTableBack() {
        CrimeWorldData data = CrimeWorldData.load(schema16());
        assertFalse(data.isReadOnlyFutureData());
        assertTrue(data.decrees().isEmpty());
        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, saved.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertTrue(saved.contains("decrees", Tag.TAG_LIST));
        assertEquals(0, saved.getList("decrees", Tag.TAG_COMPOUND).size());
    }

    @Test
    void aSeventeenWorldOpenedByASixteenBuildWouldBeFromTheFuture() {
        CompoundTag future = schema16();
        future.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        assertTrue(CrimeWorldData.load(future).isReadOnlyFutureData());
    }
}
