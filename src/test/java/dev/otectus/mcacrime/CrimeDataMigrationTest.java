package dev.otectus.mcacrime;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.fixtures.Schema7Fixture;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.bounty.BountyResolutionType;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.state.world.BountyClaimRecord;
import dev.otectus.mcacrime.state.world.BountyContractRecord;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.resources.ResourceLocation;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        // migrate() runs every remaining step, so the stamp is whatever this build writes; what this
        // test is about is the roster below it, not the number.
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, CrimeDataMigrations.schemaOf(migrated));
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
                        new net.minecraft.resources.ResourceLocation("minecraft", "overworld"), 3)),
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
                new net.minecraft.resources.ResourceLocation("minecraft", "the_nether"), 3);
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
        CrimeWorldData data = CrimeWorldData.load(legacyStore(legacyRecord(recordId, 3, true)));

        assertEquals(1, data.ledgerSize());
        assertFalse(data.isReadOnlyFutureData());
        assertEquals(-6, data.reputation(new CrimeCommunityKey(
                new net.minecraft.resources.ResourceLocation("minecraft", "overworld"), 3), OFFENDER));

        CompoundTag resaved = data.save(new CompoundTag());
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, CrimeDataMigrations.schemaOf(resaved));
        assertEquals(1, CrimeWorldData.load(resaved).ledgerSize());
    }

    /** Two rows claiming one id: both survive, the first keeps the id, and the repair is reproducible. */
    @Test
    void duplicateRecordIdsAreRepairedDeterministically() {
        UUID shared = UUID.randomUUID();
        CompoundTag store = legacyStore(legacyRecord(shared, 3, true), legacyRecord(shared, 4, false));

        CrimeWorldData first = CrimeWorldData.load(store.copy());
        CrimeWorldData second = CrimeWorldData.load(store.copy());

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

        CrimeWorldData data = CrimeWorldData.load(future.copy());

        assertTrue(data.isReadOnlyFutureData());
        assertEquals(0, data.ledgerSize(), "nothing from a future store is parsed");
        data.addRecord(CrimeRecord.load(legacyRecord(UUID.randomUUID(), 1, false)));
        assertEquals(0, data.ledgerSize(), "and nothing may be written into it");
        assertEquals(future, data.save(new CompoundTag()));
    }

    // ------------------------------------------------------------------ schema 7 (0.5.1)

    private static final ResourceLocation ASSAULT = new ResourceLocation("mcacrime", "assault");

    /**
     * A world saved by 0.5.0 has none of the social-crime collections, and must load with all six
     * empty rather than with anything synthesised from what it does have.
     */
    @Test
    void aSchemaSixStoreLoadsWithEmptySocialCrimeCollections() {
        CompoundTag store = legacyStore(legacyRecord(UUID.randomUUID(), 2, true));
        store.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_0_5_0);

        CrimeWorldData data = CrimeWorldData.load(store);

        assertFalse(data.isReadOnlyFutureData());
        assertTrue(data.criminalVillagers().isEmpty());
        assertTrue(data.bountyContracts().isEmpty());
        assertEquals(null, data.warrant(OFFENDER));
        assertEquals(null, data.criminalVillager(OFFENDER));
        assertEquals(0L, data.fenceRestockDay(OFFENDER));
        assertTrue(data.stolenGoodsByThief(OFFENDER).isEmpty());
    }

    /** Migration stamps 7 without inventing a single entry in any of the six new collections. */
    @Test
    void migratingToSevenAddsNoData() {
        CompoundTag store = legacyStore(legacyRecord(UUID.randomUUID(), 2, true));
        store.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_0_5_0);

        CompoundTag migrated = CrimeDataMigrations.migrate(store);

        // migrate() runs every remaining step, so the stamp is whatever this build writes; what this
        // test is about is that none of the six collections was invented on the way.
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, migrated.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertFalse(migrated.contains("criminalVillagers"));
        assertFalse(migrated.contains("warrants"));
        assertFalse(migrated.contains("stolenGoods"));
    }

    @Test
    void everySocialCrimeCollectionRoundTrips() {
        CrimeWorldData data = new CrimeWorldData();
        UUID thief = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();

        CriminalVillagerRecord criminal = new CriminalVillagerRecord(thief, CriminalJob.THIEF, 3L, 40L, 5L,
                true, 12345L, "mca:farmer");
        data.putCriminalVillager(criminal);
        // Stolen goods are deliberately absent from this round-trip. Loading one reaches
        // ItemStack.EMPTY, whose class initialiser needs the item registry, and there is no Minecraft
        // bootstrap on this test classpath. The record's own persistence is covered in-game instead;
        // what is testable here without a registry -- the by-thief index -- is asserted below.
        Warrant warrant = Warrant.open(UUID.randomUUID(), OFFENDER, ASSAULT, UUID.randomUUID(), 100L);
        data.putWarrant(warrant);
        BountyClaimRecord claim = new BountyClaimRecord(OFFENDER, warrant.id(), warrant.revision(),
                victim, 250L, 1000L, BountyResolutionType.CAPTURED_ALIVE);
        assertTrue(data.putBountyClaimIfAbsent("claim-key", claim));
        BountyContractRecord contract = new BountyContractRecord(contractId, OFFENDER, "Somebody",
                warrant.id(), warrant.revision(), 250L, true, true, 5000L);
        data.putBountyContract(contract);
        data.setFenceRestockDay(victim, 9L);

        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag()));

        assertEquals(criminal, loaded.criminalVillager(thief));
        assertEquals(warrant, loaded.warrant(OFFENDER));
        assertEquals(claim, loaded.bountyClaim("claim-key"));
        assertEquals(1, loaded.bountyContracts().size());
        assertEquals(contract, loaded.bountyContracts().iterator().next());
        assertEquals(9L, loaded.fenceRestockDay(victim));
    }

    /** The by-thief index is derived, so it must be rebuilt on load and maintained on removal. */
    @Test
    void removingStolenGoodsKeepsTheIndexInStep() {
        CrimeWorldData data = new CrimeWorldData();
        UUID thief = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        // A currency-only theft: the stack is left null rather than ItemStack.EMPTY, which cannot be
        // class-initialised without an item registry. Nothing here reads it.
        data.putStolenGoods(new StolenGoodsRecord(transaction, thief, UUID.randomUUID(), null, 5L, 10L));
        assertEquals(1, data.stolenGoodsByThief(thief).size());

        assertNotNull(data.removeStolenGoods(transaction));
        assertTrue(data.stolenGoodsByThief(thief).isEmpty());
        assertEquals(null, data.removeStolenGoods(transaction), "a second removal finds nothing");
    }

    /** A claim key may be recorded exactly once. This is the whole anti-double-pay mechanism. */
    @Test
    void aClaimKeyIsRecordedOnlyOnce() {
        CrimeWorldData data = new CrimeWorldData();
        BountyClaimRecord first = new BountyClaimRecord(OFFENDER, UUID.randomUUID(), 1L, UUID.randomUUID(),
                100L, 10L, BountyResolutionType.KILLED);
        BountyClaimRecord second = new BountyClaimRecord(OFFENDER, first.warrantId(), 1L, UUID.randomUUID(),
                100L, 20L, BountyResolutionType.KILLED);
        assertTrue(data.putBountyClaimIfAbsent("k", first));
        assertFalse(data.putBountyClaimIfAbsent("k", second));
        assertEquals(first, data.bountyClaim("k"));
    }

    // ------------------------------------------------------------------ schema 8 (0.6.0)

    /**
     * The 0.6.0 step stamps the version and adds nothing, because every field behind it reads its own
     * absence as the legacy answer. Running it twice must therefore produce the same tag: a step that
     * wrote anything at all would have to be idempotent by argument rather than by construction.
     */
    @Test
    void migratingToEightAddsNothingAndRepeatsCleanly() {
        CompoundTag once = CrimeDataMigrations.v7to8(Schema7Fixture.store());
        CompoundTag twice = CrimeDataMigrations.v7to8(once);

        assertEquals(CrimeDataMigrations.SCHEMA_0_6_0, once.getInt(CrimeDataMigrations.TAG_SCHEMA));
        assertEquals(once, twice);

        CompoundTag before = Schema7Fixture.store();
        before.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_0_6_0);
        assertEquals(before, once, "the only difference a v7to8 may make is the schema number");
    }

    /**
     * The whole chain, 0 to 8, on a store that never saw any of it. The point is not that it ends at
     * the current number — the earlier tests cover that — but that a world last opened before any of
     * these steps existed still arrives with its records intact rather than with a stack trace.
     */
    @Test
    void anUnversionedStoreClimbsTheWholeChainToCurrent() {
        CompoundTag migrated = CrimeDataMigrations.migrate(legacyStore(
                legacyRecord(UUID.randomUUID(), 3, true),
                legacyRecord(UUID.randomUUID(), 4, false)));

        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA, CrimeDataMigrations.schemaOf(migrated));
        assertEquals(2, migrated.getList("ledger", Tag.TAG_COMPOUND).size());
        assertFalse(migrated.contains("fenceStock"), "no 0.6.0 collection is written by the step");
        assertFalse(migrated.contains("quarantine"));
        assertEquals(CrimeDataMigrations.CURRENT_SCHEMA,
                CrimeDataMigrations.schemaOf(CrimeWorldData.load(migrated).save(new CompoundTag())));
    }

    /**
     * An over-long list is read in full, and a malformed entry is set aside rather than costing the
     * world every other criminal in it.
     *
     * <p>0.6.0 reversed the first half of this deliberately. Truncating at the cap on load did not
     * bound anything -- the entries were already written, and the store was about to be saved again --
     * it just deleted the tail of the file a little more thoroughly on every restart. The ceiling now
     * refuses the next <em>insertion</em> instead, which is the only point at which refusing is free.
     */
    @Test
    void overCapAndMalformedEntriesAreSurvivable() {
        CompoundTag store = legacyStore();
        store.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA);
        ListTag criminals = new ListTag();
        // One past the 4096 cap, plus one entry with no villager id at the front.
        criminals.add(new CompoundTag());
        for (int i = 0; i < 4200; i++) {
            criminals.add(new CriminalVillagerRecord(UUID.randomUUID(), CriminalJob.FENCE, 1L, 0L, 1L,
                    false, i, null).save());
        }
        store.put("criminalVillagers", criminals);

        CrimeWorldData data = CrimeWorldData.load(store);

        assertEquals(4200, data.criminalVillagers().size(), "the load loop deleted the tail of the file");
        assertEquals(1, data.quarantineCount(), "the unreadable entry was dropped rather than set aside");
    }
}
