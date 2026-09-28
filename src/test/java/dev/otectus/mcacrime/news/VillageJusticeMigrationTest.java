package dev.otectus.mcacrime.news;
import dev.otectus.mcacrime.state.world.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VillageJusticeMigrationTest {
    @Test void addsNoHistoricalFactsAndPreservesOlderData() {
        CompoundTag old = new CompoundTag(); old.putInt("schema", CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL);
        old.putString("sentinel", "existing data");
        CompoundTag migrated = CrimeDataMigrations.migrate(old);
        assertEquals(16, migrated.getInt("schema")); assertEquals("existing data", migrated.getString("sentinel"));
        assertFalse(migrated.contains("crimeNews")); assertEquals(15, old.getInt("schema"));
        var world = CrimeWorldData.load(migrated); assertTrue(world.news().facts.isEmpty());
        assertTrue(world.news().subscriptions.isEmpty()); assertTrue(world.news().envelopes.isEmpty());
    }
    @Test void futureSchemaRoundTripsUntouched() {
        CompoundTag future = new CompoundTag(); future.putInt("schema", CrimeDataMigrations.CURRENT_SCHEMA + 1);
        CompoundTag unknown = new CompoundTag(); unknown.putString("unknown", "preserve"); future.put("crimeNews", unknown);
        var loaded = CrimeWorldData.load(future); assertFalse(ServerMutationGate.allows(loaded));
        assertEquals(future, loaded.save(new CompoundTag()));
    }
    @Test void malformedNewsCannotReuseRevisionsOrDeliverBlankLetters() {
        var news = new CrimeNewsData(); news.publish(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), null, "minecraft:overworld/1", "reported", 1);
        CompoundTag tag = news.save(); tag.putLong("sequence", 0);
        var restored = CrimeNewsData.load(tag); assertEquals(1, restored.sequence);
        assertFalse(CrimeNewsData.validLetter(new CompoundTag()));
        CompoundTag letter = new CompoundTag(); ListTag pages = new ListTag(); pages.add(StringTag.valueOf("invalid JSON")); letter.put("pages",pages);
        assertFalse(CrimeNewsData.validLetter(letter));
    }
}
