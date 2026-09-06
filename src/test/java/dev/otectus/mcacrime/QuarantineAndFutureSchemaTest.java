package dev.otectus.mcacrime;

import dev.otectus.mcacrime.fixtures.Schema7Fixture;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rows nobody can read, and files nobody should write to (T23).
 *
 * <p>Two failures that used to be silent. A ledger row whose {@code type} would not parse was skipped
 * on load and then not written back, so a corrupt byte quietly deleted a case from a player's history
 * and nothing anywhere recorded that it had happened; worse, before the loader threw at all, such a row
 * loaded as a case against a real player for no offence in particular, which the dossier then showed
 * and a fine then settled. And a store from a newer jar was refused write-by-write deep inside
 * {@code CrimeWorldData}, which is far too late — by then a guard had taken the item or a fine had
 * charged the player.
 *
 * <p>So: unreadable rows are set aside verbatim and re-emitted on save, and the read-only state is
 * asked once, up front, through {@link ServerMutationGate}.
 */
class QuarantineAndFutureSchemaTest {

    // ------------------------------------------------------------------ quarantine

    @Test
    void theBadRowIsQuarantinedAndEveryOtherRowLoads() {
        CrimeWorldData data = CrimeWorldData.load(Schema7Fixture.store(), RegistryAccess.EMPTY);

        assertEquals(4, data.ledgerSize(), "one unreadable row cost the offender their whole history");
        assertTrue(data.recordById(Schema7Fixture.CASE_EARLY).isPresent());
        assertTrue(data.recordById(Schema7Fixture.CASE_ALSO_EARLY).isPresent());
        assertTrue(data.recordById(Schema7Fixture.CASE_LATER).isPresent());
        assertTrue(data.recordById(Schema7Fixture.CASE_MANDATORY_CUSTODY).isPresent());
        assertTrue(data.recordById(Schema7Fixture.CASE_UNPARSABLE_TYPE).isEmpty(),
                "a row with no parseable crime type must not become a case against somebody");

        assertEquals(1, data.quarantineCount(), "the unreadable row was dropped rather than set aside");
        CompoundTag held = data.quarantined().get(0);
        assertTrue(held.getString("reason").contains("type"), "the reason should name what went wrong");
        assertEquals(Schema7Fixture.CASE_UNPARSABLE_TYPE, held.getCompound("tag").getUUID("id"),
                "the original row has to be kept verbatim, or there is nothing to repair");
    }

    @Test
    void aQuarantinedRowSurvivesASaveAndReload() {
        CrimeWorldData once = CrimeWorldData.load(Schema7Fixture.store(), RegistryAccess.EMPTY);
        CrimeWorldData twice = CrimeWorldData.load(
                once.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        assertEquals(1, twice.quarantineCount(),
                "writing the store back out threw away the evidence it had just collected");
        assertEquals(Schema7Fixture.CASE_UNPARSABLE_TYPE,
                twice.quarantined().get(0).getCompound("tag").getUUID("id"));
        assertEquals(4, twice.ledgerSize(), "a quarantined row must not come back as a real case either");
    }

    @Test
    void aRowWithNoOffenderIsQuarantinedToo() {
        CompoundTag store = Schema7Fixture.store();
        CompoundTag orphan = new CompoundTag();
        orphan.putUUID("id", UUID.randomUUID());
        orphan.putString("type", "mcacrime:theft");
        store.getList("ledger", Tag.TAG_COMPOUND).add(orphan);

        CrimeWorldData data = CrimeWorldData.load(store, RegistryAccess.EMPTY);

        assertEquals(4, data.ledgerSize());
        assertEquals(2, data.quarantineCount(), "a case against nobody is not a case");
    }

    // ------------------------------------------------------------------ the gate

    @Test
    void aStoreFromTheFutureClosesTheGate() {
        CompoundTag store = Schema7Fixture.store();
        store.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);

        CrimeWorldData data = CrimeWorldData.load(store, RegistryAccess.EMPTY);

        assertTrue(data.isReadOnlyFutureData());
        assertFalse(ServerMutationGate.allows(data),
                "every write-side entry point asks this question, and it has to answer no");
        assertEquals(0, data.ledgerSize(), "a store this build cannot understand must not be parsed at all");
    }

    @Test
    void aStoreFromTheFutureIsHandedBackByteForByte() {
        CompoundTag store = Schema7Fixture.store();
        store.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        // A structure this build knows nothing about, which is the whole reason not to rewrite the file.
        CompoundTag invented = new CompoundTag();
        invented.putString("shape", "something 0.7.0 added");
        store.put("parolePanels", invented);

        CompoundTag written = CrimeWorldData.load(store, RegistryAccess.EMPTY)
                .save(new CompoundTag(), RegistryAccess.EMPTY);

        assertEquals(store, written, "the store was rewritten in this build's own shape, losing the rest");
        assertEquals("something 0.7.0 added",
                written.getCompound("parolePanels").getString("shape"));
    }

    @Test
    void anOrdinaryStoreLeavesTheGateOpen() {
        CrimeWorldData data = CrimeWorldData.load(Schema7Fixture.store(), RegistryAccess.EMPTY);

        assertFalse(data.isReadOnlyFutureData());
        assertFalse(data.isLoadFailed());
        assertTrue(ServerMutationGate.allows(data));
    }

    @Test
    void aNullStoreIsNeverWritable() {
        assertFalse(ServerMutationGate.allows((CrimeWorldData) null));
    }

    @Test
    void aReadOnlyStoreRefusesEveryWrite() {
        CompoundTag store = Schema7Fixture.store();
        store.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        CrimeWorldData data = CrimeWorldData.load(store, RegistryAccess.EMPTY);

        // The gate is the mechanism; this is the backstop under it, for the caller that forgets to ask.
        data.bindSentence(Schema7Fixture.OFFENDER, UUID.randomUUID(), 0L);
        ListTag ledger = data.save(new CompoundTag(), RegistryAccess.EMPTY)
                .getList("ledger", Tag.TAG_COMPOUND);

        assertEquals(5, ledger.size(), "a write got through to a store that was never parsed");
    }
}
