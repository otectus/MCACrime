package dev.otectus.mcacrime;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upgrade path an existing world takes exactly once, exercised on hand-built tags with no server
 * involved.
 *
 * <p>The property under test throughout is that migration <b>never invents information</b>. A legacy
 * record knows a village integer but not a dimension, and knows it was witnessed but not by whom;
 * both gaps must be recorded as gaps rather than filled with a plausible guess.
 */
class CrimeDataMigrationTest {

    private static final UUID OFFENDER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** A ledger row in the original, unversioned shape: bare village id, boolean witnessed, nothing else. */
    private static CompoundTag legacyRecord(UUID id, int villageId, boolean witnessed) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("offender", OFFENDER);
        tag.putString("type", CrimeIds.HARM_VILLAGER.toString());
        tag.putInt("villageId", villageId);
        tag.putBoolean("witnessed", witnessed);
        tag.putLong("timeCommitted", 1000L);
        tag.putLong("heatGenerated", 15L);
        tag.putLong("karmaDelta", -10L);
        tag.putLong("fineAmount", 0L);
        tag.putLong("jailTicks", 0L);
        tag.putString("resolution", Resolution.UNRESOLVED.name());
        return tag;
    }

    /** A whole schema-0 store: no schema key, integer-keyed standing, legacy ledger rows. */
    private static CompoundTag legacyStore(CompoundTag... records) {
        CompoundTag tag = new CompoundTag();
        ListTag ledger = new ListTag();
        for (CompoundTag record : records) {
            ledger.add(record);
        }
        tag.put("ledger", ledger);

        CompoundTag villages = new CompoundTag();
        CompoundTag perPlayer = new CompoundTag();
        perPlayer.putInt(OFFENDER.toString(), -6);
        villages.put("3", perPlayer);
        tag.put("villageReputation", villages);
        return tag;
    }

    // ------------------------------------------------------------------ schema plumbing

    /**
     * Schema 6 adds the holding-cell roster, empty.
     *
     * <p>Empty is the point, and it is worth an assertion rather than a comment. The roster records
     * boxes this mod placed and therefore owns the right to delete; the jail anchors already in an old
     * world point at structures players built by hand. Seeding one from the other would mean the first
     * release after this update demolished somebody's jail.
     */
    @Test
    void schemaSixAddsAnEmptyHoldingCellRoster() {
        CompoundTag five = new CompoundTag();
        five.putInt(CrimeDataMigrations.TAG_SCHEMA, 5);
        ListTag anchors = new ListTag();
        CompoundTag anchor = new CompoundTag();
        anchor.putInt("x", 10);
        anchor.putInt("y", 64);
        anchor.putInt("z", 10);
        anchor.putString("dim", "minecraft:overworld");
        anchor.putInt("radius", 8);
        anchors.add(anchor);
        five.put("jailRoster", anchors);

        CompoundTag migrated = CrimeDataMigrations.migrate(five);

        assertEquals(6, CrimeDataMigrations.schemaOf(migrated));
        assertTrue(migrated.contains("holdingCells", Tag.TAG_LIST));
        assertEquals(0, migrated.getList("holdingCells", Tag.TAG_COMPOUND).size(),
                "an assigned jail must never be mistaken for a cell this mod built");
        assertEquals(1, migrated.getList("jailRoster", Tag.TAG_COMPOUND).size(),
                "the existing jail roster is left exactly as it was");
    }


    @Test
    void anUnversionedStoreReadsAsSchemaZero() {
        assertEquals(0, CrimeDataMigrations.schemaOf(new CompoundTag()));
    }

    @Test
    void migrationStampsTheCurrentSchema() {
        CompoundTag migrated = CrimeDataMigrations.migrate(legacyStore(
                legacyRecord(UUID.randomUUID(), 3, true)));

        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, CrimeDataMigrations.schemaOf(migrated));
    }

    @Test
    void anAlreadyCurrentStoreIsNotTouched() {
        CompoundTag current = new CompoundTag();
        current.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA);
        current.putString("marker", "untouched");

        assertEquals(current, CrimeDataMigrations.migrate(current));
    }

    // ------------------------------------------------------------------ 0 -> 1

    @Test
    void legacyVillageIdsBecomeOverworldCommunities() {
        UUID recordId = UUID.randomUUID();
        CompoundTag migrated = CrimeDataMigrations.v0to1(legacyStore(legacyRecord(recordId, 3, true)));

        CompoundTag record = migrated.getList("ledger", Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(Optional.of(new CrimeCommunityKey(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 3)),
                CrimeCommunityKey.load(record.getCompound("community")));
        assertEquals("assumed_overworld",
                record.getCompound("context").getString(CrimeContext.LEGACY_MIGRATION));
    }

    @Test
    void theStandingStoreIsRekeyedByCommunity() {
        CompoundTag migrated = CrimeDataMigrations.v0to1(legacyStore());
        CompoundTag villages = migrated.getCompound("villageReputation");

        assertTrue(villages.contains("minecraft:overworld/3"));
        assertFalse(villages.contains("3"));
        assertEquals(-6, villages.getCompound("minecraft:overworld/3").getInt(OFFENDER.toString()));
    }

    /** The assumption is recorded as an assumption, so later code never mistakes it for a fact. */
    @Test
    void theAssumedDimensionIsStampedRatherThanHidden() {
        CompoundTag migrated = CrimeDataMigrations.migrate(legacyStore(
                legacyRecord(UUID.randomUUID(), 5, false)));
        CrimeRecord record = CrimeRecord.load(
                migrated.getList("ledger", Tag.TAG_COMPOUND).getCompound(0));

        assertEquals(Optional.of("assumed_overworld"), record.view().context(CrimeContext.LEGACY_MIGRATION));
    }

    @Test
    void aRecordThatAlreadyHasACommunityIsLeftAlone() {
        CompoundTag record = legacyRecord(UUID.randomUUID(), 3, true);
        CrimeCommunityKey nether = new CrimeCommunityKey(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"), 3);
        record.put("community", nether.save());

        CompoundTag migrated = CrimeDataMigrations.v0to1(legacyStore(record));
        CompoundTag out = migrated.getList("ledger", Tag.TAG_COMPOUND).getCompound(0);

        assertEquals(Optional.of(nether), CrimeCommunityKey.load(out.getCompound("community")));
        assertFalse(out.getCompound("context").contains(CrimeContext.LEGACY_MIGRATION));
    }

    // ------------------------------------------------------------------ 1 -> 2

    /**
     * The privacy rule, in data form. A pre-identity record stays marked witnessed, but nobody is
     * named — so no villager is later handed knowledge of a crime they were never recorded as seeing.
     */
    @Test
    void aWitnessedLegacyRecordGetsNoFabricatedWitnesses() {
        CompoundTag migrated = CrimeDataMigrations.migrate(legacyStore(
                legacyRecord(UUID.randomUUID(), 3, true)));
        CrimeRecord record = CrimeRecord.load(
                migrated.getList("ledger", Tag.TAG_COMPOUND).getCompound(0));

        assertTrue(record.witnessed());
        assertTrue(record.witnessIds().isEmpty());
        assertEquals(Optional.of("true"),
                record.view().context(CrimeContext.LEGACY_WITNESS_IDENTITY_MISSING));
    }

    @Test
    void anUnwitnessedLegacyRecordIsNotFlagged() {
        CompoundTag migrated = CrimeDataMigrations.migrate(legacyStore(
                legacyRecord(UUID.randomUUID(), 3, false)));
        CrimeRecord record = CrimeRecord.load(
                migrated.getList("ledger", Tag.TAG_COMPOUND).getCompound(0));

        assertFalse(record.witnessed());
        assertTrue(record.view().context(CrimeContext.LEGACY_WITNESS_IDENTITY_MISSING).isEmpty());
    }

    @Test
    void existingDispositionsAndDeltasSurviveExactly() {
        UUID recordId = UUID.randomUUID();
        CompoundTag legacy = legacyRecord(recordId, 3, true);
        legacy.putString("resolution", Resolution.SERVED.name());
        legacy.putLong("fineAmount", 40L);

        CompoundTag migrated = CrimeDataMigrations.migrate(legacyStore(legacy));
        CrimeRecord record = CrimeRecord.load(
                migrated.getList("ledger", Tag.TAG_COMPOUND).getCompound(0));

        assertEquals(recordId, record.id());
        assertEquals(OFFENDER, record.offender());
        assertEquals(Resolution.SERVED, record.resolution());
        assertEquals(40L, record.fineAmount());
        assertEquals(-10L, record.karmaDelta());
        assertEquals(15L, record.heatGenerated());
        assertEquals(1000L, record.timeCommitted());
        assertEquals(0L, record.resolutionRevision());
    }

    // ------------------------------------------------------------------ 2 -> 3

    @Test
    void theOutboxAndDedupeStoresStartEmpty() {
        CompoundTag migrated = CrimeDataMigrations.migrate(legacyStore());

        assertTrue(migrated.contains("outbox", Tag.TAG_LIST));
        assertEquals(0, migrated.getList("outbox", Tag.TAG_COMPOUND).size());
        assertEquals(0, migrated.getList("deadLetters", Tag.TAG_COMPOUND).size());
        assertTrue(migrated.getCompound("dedupe").isEmpty());
    }

    // ------------------------------------------------------------------ end to end through the store

    @Test
    void aLegacyStoreLoadsAndReSavesAtTheCurrentSchema() {
        UUID recordId = UUID.randomUUID();
        CrimeWorldData data = CrimeWorldData.load(legacyStore(legacyRecord(recordId, 3, true)), RegistryAccess.EMPTY);

        assertEquals(1, data.ledgerSize());
        assertFalse(data.isReadOnlyFutureData());
        assertEquals(-6, data.reputation(new CrimeCommunityKey(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 3), OFFENDER));

        CompoundTag resaved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, CrimeDataMigrations.schemaOf(resaved));
        assertEquals(1, CrimeWorldData.load(resaved, RegistryAccess.EMPTY).ledgerSize());
    }

    /** Two rows claiming one id: both survive, the first keeps the id, and the repair is reproducible. */
    @Test
    void duplicateRecordIdsAreRepairedDeterministically() {
        UUID shared = UUID.randomUUID();
        CompoundTag store = legacyStore(legacyRecord(shared, 3, true), legacyRecord(shared, 4, false));

        CrimeWorldData first = CrimeWorldData.load(store.copy(), RegistryAccess.EMPTY);
        CrimeWorldData second = CrimeWorldData.load(store.copy(), RegistryAccess.EMPTY);

        assertEquals(2, first.ledgerSize(), "neither record may be silently dropped");
        List<CrimeRecord> records = first.recordsForOffender(OFFENDER);
        assertEquals(2, records.size());

        UUID repairedFirst = records.stream().map(CrimeRecord::id)
                .filter(id -> !id.equals(shared)).findFirst().orElseThrow();
        UUID repairedSecond = second.recordsForOffender(OFFENDER).stream().map(CrimeRecord::id)
                .filter(id -> !id.equals(shared)).findFirst().orElseThrow();

        assertNotEquals(shared, repairedFirst);
        assertEquals(repairedFirst, repairedSecond, "loading the same file twice must repair the same way");
        assertTrue(first.recordById(repairedFirst).orElseThrow()
                .view().context(CrimeContext.DUPLICATE_ID_REPAIRED).isPresent());
    }

    /**
     * A store from a newer jar is carried through completely untouched. Parsing it would mean guessing
     * at structures this build does not know, and saving the guess would destroy them.
     */
    @Test
    void aStoreFromTheFutureIsPreservedAndReadOnly() {
        CompoundTag future = legacyStore(legacyRecord(UUID.randomUUID(), 3, true));
        future.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 5);
        future.putString("somethingNewAndUnknown", "keep me");

        CrimeWorldData data = CrimeWorldData.load(future.copy(), RegistryAccess.EMPTY);

        assertTrue(data.isReadOnlyFutureData());
        assertEquals(0, data.ledgerSize(), "nothing from a future store is parsed");
        data.addRecord(CrimeRecord.load(legacyRecord(UUID.randomUUID(), 1, false)));
        assertEquals(0, data.ledgerSize(), "and nothing may be written into it");
        assertEquals(future, data.save(new CompoundTag(), RegistryAccess.EMPTY));
    }
}
