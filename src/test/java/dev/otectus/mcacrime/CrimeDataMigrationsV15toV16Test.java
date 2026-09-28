package dev.otectus.mcacrime;

import dev.otectus.mcacrime.fixtures.Schema7Fixture;
import dev.otectus.mcacrime.news.CrimeNewsData;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.7.5 village-justice step: schema 16 adds the village-news projection and writes nothing else.
 *
 * <p>Until this step existed, a schema-15 world reached 16 only through {@code migrate}'s final stamp,
 * with no documented step saying what 16 means. The refusal worth pinning is the tempting one: the case
 * ledger already holds everything a newspaper could print, and seeding {@code crimeNews} from it would
 * be quick. It would also be back-dated news of reports nobody made, so the step stamps the version and
 * the news starts empty.
 */
class CrimeDataMigrationsV15toV16Test {

    /** A store in the shape a schema-15 build writes: every earlier step applied, stamped 15, no news. */
    private static CompoundTag schema15() {
        CompoundTag tag = CrimeDataMigrations.migrate(Schema7Fixture.store());
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL);
        assertFalse(tag.contains("crimeNews"), "a schema-15 store predates the news projection");
        return tag;
    }

    @Test
    void villageJusticeIsSixteenAndFollowsFifteenDirectly() {
        assertEquals(16, CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE);
        assertEquals(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL + 1, CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE);
        assertTrue(CrimeDataMigrations.CURRENT_SCHEMA >= CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE);
    }

    @Test
    void theStepStampsSixteenAndChangesNothingElse() {
        CompoundTag before = schema15();
        CompoundTag after = CrimeDataMigrations.v15to16(before);

        assertEquals(CrimeDataMigrations.SCHEMA_VILLAGE_JUSTICE, after.getInt(CrimeDataMigrations.TAG_SCHEMA));
        CompoundTag withoutStamp = after.copy();
        withoutStamp.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL);
        assertEquals(before, withoutStamp, "the step wrote something besides the version");
        assertFalse(after.contains("crimeNews"), "news was seeded; the migration invents no reports");
        assertEquals(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL, before.getInt(CrimeDataMigrations.TAG_SCHEMA),
                "the input tag was mutated in place");
    }

    @Test
    void aSchemaFifteenWorldMigratesThroughTheStep() {
        CompoundTag migrated = CrimeDataMigrations.migrate(schema15());
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, migrated.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertFalse(migrated.contains("crimeNews"));
    }

    @Test
    void aSchemaFifteenWorldLoadsWithEmptyNewsAndWritesItBack() {
        CrimeWorldData data = CrimeWorldData.load(schema15());

        assertFalse(data.isReadOnlyFutureData(), "a schema-15 world is an older world, not a newer one");
        CrimeNewsData news = data.news();
        assertTrue(news.facts.isEmpty(), "no case became news on upgrade");
        assertTrue(news.subscriptions.isEmpty());
        assertTrue(news.envelopes.isEmpty());
        assertEquals(0L, news.sequence);

        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, saved.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertTrue(saved.contains("crimeNews", Tag.TAG_COMPOUND), "the projection is written from the first save");
        CompoundTag written = saved.getCompound("crimeNews");
        for (String list : new String[] {"facts", "subscriptions", "envelopes"}) {
            assertEquals(0, written.getList(list, Tag.TAG_COMPOUND).size(), list + " is not empty");
        }

        CrimeWorldData reloaded = CrimeWorldData.load(saved);
        assertEquals(news.worldId, reloaded.news().worldId,
                "the world id minted on upgrade must persist, or mail receipts would not survive a restart");
    }
}
