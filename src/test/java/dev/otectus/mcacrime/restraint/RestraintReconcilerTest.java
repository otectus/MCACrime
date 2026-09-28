package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.property.PropertyAccess;
import dev.otectus.mcacrime.property.PropertyAccessRule;
import dev.otectus.mcacrime.property.PropertyActor;
import dev.otectus.mcacrime.property.PropertyOwnerKind;
import dev.otectus.mcacrime.property.PropertyPolicy;
import dev.otectus.mcacrime.property.PropertyReceipt;
import dev.otectus.mcacrime.property.PropertySource;
import dev.otectus.mcacrime.property.TransferAttribution;
import dev.otectus.mcacrime.facility.TownsteadBuildingRef;
import dev.otectus.mcacrime.ransom.PayerTier;
import dev.otectus.mcacrime.ransom.RansomState;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The impure half of the 14 → 15 upgrade, one fixture per §21.5 case.
 *
 * <p>The failure this whole class exists to prevent is stated in the specification: "a migration that
 * creates a free extra cuff on every login fails". Every conversion is therefore asserted twice —
 * once for producing the right thing, and once for producing it exactly once.
 *
 * <p>The other half is that everything the physical model does not own must come through untouched:
 * sentences, ransom demands, property receipts, cell journals and care state are legal facts, and a
 * physical migration that disturbed one of them would be rewriting history to fit new gear.
 */
class RestraintReconcilerTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final BlockPos HOLD = new BlockPos(12, 64, -8);
    private static final BlockPos CHEST = new BlockPos(10, 64, 10);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

    private static CrimeWorldData store() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA);
        tag.put("ledger", new ListTag());
        return CrimeWorldData.load(tag);
    }

    private static CustodyRecord kidnapping(UUID captive, RestraintType restraint, boolean captiveIsPlayer) {
        CustodyRecord record = new CustodyRecord(captive, captiveIsPlayer, false,
                CustodyOwner.kidnapper(UUID.randomUUID()), 40L, HOLD, OVERWORLD);
        record.setLegacyRestraint(restraint);
        return record;
    }

    private static CustodyRecord arrest(UUID captive, RestraintType restraint, boolean captiveIsPlayer) {
        CustodyRecord record = new CustodyRecord(captive, captiveIsPlayer, true,
                CustodyOwner.guard(UUID.randomUUID()), 40L, HOLD, OVERWORLD);
        record.setLegacyRestraint(restraint);
        return record;
    }

    private static AppliedRestraint arms(CrimeWorldData data, UUID subject) {
        PhysicalRestraintState state = data.physicalRestraint(subject);
        assertNotNull(state, "no physical state was written for " + subject);
        return state.slot(RestraintSlot.ARMS).orElseThrow();
    }

    // ------------------------------------------------------------------ 1-4: each old restraint

    @Test
    void ropeBecomesArmTape() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        data.putCustody(kidnapping(captive, RestraintType.ROPE, true));

        RestraintMigrationReconciler.reconcile(data, 1000L);

        AppliedRestraint applied = arms(data, captive);
        assertEquals(RestraintDefinitions.DUCK_TAPE_ARMS, applied.definitionId());
        assertEquals(RestraintDefinitions.ITEM_DUCK_TAPE.toString(),
                applied.itemSnapshot().getString("id"));
        assertEquals(AppliedRestraint.Provenance.LEGACY_CONVERSION, applied.provenance());
        assertEquals(AppliedRestraint.ReturnPolicy.NONE, applied.returnPolicy(),
                "release must not mint an item nobody supplied");
    }

    @Test
    void cuffsBecomeArmShacklesKeepingTheProtectedItem() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        data.putCustody(kidnapping(captive, RestraintType.CUFFS, true));

        RestraintMigrationReconciler.reconcile(data, 1000L);

        AppliedRestraint applied = arms(data, captive);
        assertEquals(RestraintDefinitions.SHACKLES_ARMS, applied.definitionId());
        assertEquals("mcacrime:restraint_cuffs", applied.itemSnapshot().getString("id"));
        assertEquals(15, applied.remainingDurability(), "a converted instance starts at full durability");
    }

    @Test
    void lockedCuffsBecomeArmHandcuffsKeepingTheProtectedItem() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        data.putCustody(kidnapping(captive, RestraintType.LOCKED_CUFFS, true));

        RestraintMigrationReconciler.reconcile(data, 1000L);

        AppliedRestraint applied = arms(data, captive);
        assertEquals(RestraintDefinitions.HANDCUFFS_ARMS, applied.definitionId());
        assertEquals("mcacrime:restraint_locked_cuffs", applied.itemSnapshot().getString("id"));
        assertFalse(applied.itemSnapshot().contains("tag"),
                "the old record stored no enchantments, so none may be claimed");
    }

    @Test
    void noRestraintBecomesNoGear() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        data.putCustody(arrest(captive, RestraintType.NONE, true));

        RestraintMigrationReconciler.Result result = RestraintMigrationReconciler.reconcile(data, 1000L);

        assertEquals(0, result.restraintsConverted());
        PhysicalRestraintState state = data.physicalRestraint(captive);
        assertTrue(state == null || !state.restrained(),
                "a custody with no restraint was a custody with no restraint");
    }

    // ------------------------------------------------------------------ 5-7: who is being held

    @Test
    void aLawfulPlayerArrestConvertsAsLawfulAndGetsNoTether() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        CustodyRecord record = arrest(captive, RestraintType.LOCKED_CUFFS, true);
        UUID custodyId = record.getCustodyId();
        data.putCustody(record);

        RestraintMigrationReconciler.reconcile(data, 1000L);

        AppliedRestraint applied = arms(data, captive);
        assertEquals(AppliedRestraint.ApplicationContext.LAWFUL, applied.context());
        assertEquals(custodyId, applied.custodyId(), "the gear knows which captivity it belongs to");
        assertEquals(RestraintApplier.Kind.NPC, applied.applier().kind(), "a guard is a villager");
        assertTrue(data.tethersForSubject(captive).isEmpty(),
                "a lawful hold is a cell or an escort, not a rope to a fence post");
    }

    @Test
    void anUnlawfulPlayerCustodyConvertsAsUnlawfulAndKeepsItsHoldAsALegacyTether() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        CustodyRecord record = kidnapping(captive, RestraintType.CUFFS, true);
        data.putCustody(record);

        RestraintMigrationReconciler.Result result = RestraintMigrationReconciler.reconcile(data, 1000L);

        assertEquals(1, result.tethersCreated());
        AppliedRestraint applied = arms(data, captive);
        assertEquals(AppliedRestraint.ApplicationContext.UNLAWFUL, applied.context());
        assertEquals(RestraintApplier.Kind.PLAYER, applied.applier().kind());

        TetherRecord tether = data.tethersForSubject(captive).get(0);
        assertEquals(TetherKind.LEGACY_HOLD, tether.kind());
        assertEquals(HOLD, tether.anchor().orElseThrow());
        assertNull(tether.chainOwner(), "no chain item was ever taken from anybody");
        assertFalse(tether.returnOnRelease(), "so ending it owes nobody one");
        assertEquals(tether.id(), data.physicalRestraint(captive).tetherId(),
                "the subject's state has to point at the tether, or nothing arbitrates it");
    }

    @Test
    void aLawfulNpcCustodyConvertsTheSameWayAndStaysAVillager() {
        CrimeWorldData data = store();
        UUID villager = UUID.randomUUID();
        data.putCustody(arrest(villager, RestraintType.CUFFS, false));

        RestraintMigrationReconciler.reconcile(data, 1000L);

        PhysicalRestraintState state = data.physicalRestraint(villager);
        assertFalse(state.subjectIsPlayer(), "one table holds both, and the flag is what tells them apart");
        assertEquals(RestraintDefinitions.SHACKLES_ARMS, arms(data, villager).definitionId());
    }

    @Test
    void anUnloadedNpcIsConvertedFromItsWorldRowWithoutBeingLoaded() {
        CrimeWorldData data = store();
        UUID villager = UUID.randomUUID();
        CustodyRecord record = kidnapping(villager, RestraintType.ROPE, false);
        record.setVirtual(true);
        data.putCustody(record);

        RestraintMigrationReconciler.reconcile(data, 1000L);

        assertEquals(RestraintDefinitions.DUCK_TAPE_ARMS, arms(data, villager).definitionId());
        assertTrue(data.getCustody(villager).isVirtual(),
                "virtual containment is a legal fact and is not disturbed");
        assertEquals(OVERWORLD, data.physicalRestraint(villager).dimension(),
                "the row remembers where they were, which is the point of not needing the entity");
    }

    // ------------------------------------------------------------------ 8-9: care, sentences

    @Test
    void recoveryAndCareStateComeThroughUntouched() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        CustodyRecord record = arrest(captive, RestraintType.CUFFS, true);
        record.enterRecovery("starving", 4242L);
        data.putCustody(record);

        RestraintMigrationReconciler.reconcile(data, 1000L);

        CustodyRecord after = data.getCustody(captive);
        assertTrue(after.isInRecovery(), "a suspended confinement stays suspended");
        assertEquals("starving", after.getRecoveryReason());
        assertEquals(4242L, after.getRecoverySince());
    }

    @Test
    void anActiveSentenceKeepsItsIdentityAndItsClock() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        UUID sentence = UUID.randomUUID();
        CustodyRecord record = arrest(captive, RestraintType.LOCKED_CUFFS, true);
        record.setSentenceId(sentence);
        record.setRemainingJailTicks(6000L);
        record.setRealTicksHeld(1200L);
        data.putCustody(record);

        RestraintMigrationReconciler.reconcile(data, 1000L);

        CustodyRecord after = data.getCustody(captive);
        assertEquals(sentence, after.getSentenceId());
        assertEquals(6000L, after.getRemainingJailTicks(), "time served is not rewritten by a migration");
        assertEquals(1200L, after.getRealTicksHeld());
        assertEquals(after.getCustodyId(), arms(data, captive).custodyId());
    }

    // ------------------------------------------------------------------ 10-12: the untouched tables

    @Test
    void anOpenRansomDemandIsUntouched() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        data.putCustody(kidnapping(captive, RestraintType.CUFFS, true));
        RansomState ransom = new RansomState(UUID.randomUUID(), captive, UUID.randomUUID(),
                UUID.randomUUID(), PayerTier.SPOUSE, 250L, 100L, 9000L);
        data.putRansom(ransom);

        RestraintMigrationReconciler.reconcile(data, 1000L);

        RansomState after = data.getRansomForVictim(captive);
        assertNotNull(after, "the demand disappeared");
        assertEquals(250L, after.getAmount(), "a balance is not a physical fact");
        assertEquals(ransom.getDemandId(), after.getDemandId());
        assertEquals(9000L, after.getExpiresAtGameTime());
    }

    @Test
    void propertyReceiptsAreUntouched() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        data.putCustody(arrest(captive, RestraintType.CUFFS, true));
        PropertyPolicy policy = PropertyPolicy.container(OVERWORLD, CHEST,
                new TownsteadBuildingRef(OVERWORLD, 3, 7, 12), PropertyOwnerKind.VILLAGE, null,
                PropertyAccessRule.RESIDENTS, false, PropertySource.MANUAL, "operator", 100L);
        TransferAttribution.CommittedTransfer transfer = new TransferAttribution.CommittedTransfer(
                ALICE, PropertyActor.Kind.PLAYER, OVERWORLD, CHEST, TransferAttribution.Direction.OUT,
                "Bread", "minecraft:bread#", 4, 500L, 0);
        PropertyReceipt receipt = PropertyReceipt.lost(TransferAttribution.of(transfer, policy,
                PropertyAccess.Decision.denied("only residents may take from this"), null, true),
                policy, UUID.randomUUID());
        assertTrue(data.putPropertyReceipt(receipt));

        RestraintMigrationReconciler.reconcile(data, 1000L);

        assertEquals(List.of(receipt), data.propertyReceipts(),
                "who owes what for a stolen loaf is not something a restraint migration decides");
    }

    @Test
    void aGeneratedCellJournalIsUntouched() {
        CrimeWorldData data = store();
        UUID prisoner = UUID.randomUUID();
        data.putCustody(arrest(prisoner, RestraintType.LOCKED_CUFFS, true));
        HoldingCell remains = new HoldingCell(prisoner, UUID.randomUUID(), new BlockPos(20, 64, 20),
                OVERWORLD, 3, 500L, Map.of(), Map.of());
        data.putPendingCellRestoration(remains);

        RestraintMigrationReconciler.reconcile(data, 1000L);

        assertEquals(1, data.pendingCellRestorations().size(),
                "an unfinished cell restoration is still owed to the world");
        assertEquals(new BlockPos(20, 64, 20), data.pendingCellRestoration(prisoner).anchor());
    }

    @Test
    void anActiveLockMinigameEscapeIsCancelledAndItsCombinationArchived() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        CustodyRecord record = kidnapping(captive, RestraintType.LOCKED_CUFFS, true);
        record.setEscapeActive(true);
        record.setEscapeProgress(90);
        // A valid three-pin combination: CuffLockProgress rejects anything else, so a made-up array
        // would leave the record with no combination and quietly assert nothing.
        record.setCuffCombination(new byte[]{2, 0, 1});
        data.putCustody(record);

        RestraintMigrationReconciler.Result result = RestraintMigrationReconciler.reconcile(data, 1000L);

        assertEquals(1, result.escapesCancelled());
        assertEquals(1, result.combinationsArchived());
        CustodyRecord after = data.getCustody(captive);
        assertFalse(after.isEscapeActive(), "the engine that scored that attempt is gone");
        assertEquals(0, after.getEscapeProgress());
        assertEquals(0, after.getCuffCombination().length, "the combination unlocks nothing now");
        assertEquals(3, data.archived(RestraintMigrationReconciler.ARCHIVE_SECTION)
                .getByteArray(captive.toString()).length, "but it is kept, not thrown away");
        assertEquals(0L, after.getEscapeCooldownUntil(), "and nobody is charged for the cancellation");
    }

    // ------------------------------------------------------------------ idempotence

    @Test
    void repeatedLoadCreatesNoExtraItem() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        data.putCustody(kidnapping(captive, RestraintType.CUFFS, true));

        RestraintMigrationReconciler.Result first = RestraintMigrationReconciler.reconcile(data, 1000L);
        AppliedRestraint afterFirst = arms(data, captive);

        // Three more passes, including across a save and reload, which is what a login actually is.
        RestraintMigrationReconciler.Result second = RestraintMigrationReconciler.reconcile(data, 2000L);
        CrimeWorldData reloaded = CrimeWorldData.load(data.save(new CompoundTag()));
        RestraintMigrationReconciler.Result third = RestraintMigrationReconciler.reconcile(reloaded, 3000L);

        assertTrue(first.ran());
        assertFalse(second.ran(), "the marker is what makes the second pass a no-op");
        assertFalse(third.ran(), "and the marker has to survive the save file");
        assertEquals(1, reloaded.physicalRestraints().size());
        assertEquals(afterFirst, arms(reloaded, captive),
                "the same instance, not a second pair of cuffs with a new id");
        assertEquals(1, reloaded.tethersForSubject(captive).size());
        assertEquals(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL, reloaded.reconciledSchema());
    }

    @Test
    void anAlreadyOccupiedArmSlotIsNeverDoubledUp() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        data.putCustody(kidnapping(captive, RestraintType.CUFFS, true));
        AppliedRestraint theirs = AppliedRestraint.of(
                RestraintDefinitions.get(RestraintDefinitions.SHACKLES_ARMS).orElseThrow(), null,
                RestraintApplier.system(), AppliedRestraint.ApplicationContext.LAWFUL,
                AppliedRestraint.Provenance.PLAYER_OWNED, AppliedRestraint.ReturnPolicy.NONE, null, 5L);
        data.putPhysicalRestraint(PhysicalRestraintState.empty(captive, true, OVERWORLD)
                .with(RestraintSlot.ARMS, theirs));

        RestraintMigrationReconciler.Result result = RestraintMigrationReconciler.reconcile(data, 1000L);

        assertEquals(0, result.restraintsConverted());
        assertEquals(theirs, arms(data, captive), "existing gear must not be replaced by a conversion");
    }

    @Test
    void futureSchemaStaysQuarantinedAndNothingIsConverted() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        tag.put("ledger", new ListTag());
        CrimeWorldData data = CrimeWorldData.load(tag);

        RestraintMigrationReconciler.Result result = RestraintMigrationReconciler.reconcile(data, 1000L);

        assertFalse(result.ran(), "a store this build never parsed must not be written to");
        assertTrue(data.physicalRestraints().isEmpty());
        assertEquals(0, data.reconciledSchema());
    }

    @Test
    void aNullStoreIsHarmless() {
        assertFalse(RestraintMigrationReconciler.reconcile(null, 0L).ran());
    }

    // ------------------------------------------------------------------ the wiring

    /**
     * A schema-14 store, as it would arrive from a pre-0.7.5 world: one cuffed kidnapping victim,
     * no {@code custodyId}, no {@code generation}, and none of the five physical tables.
     */
    private static CompoundTag schema14(UUID captive) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.SCHEMA_PROPERTY_LAW);
        tag.put("ledger", new ListTag());
        CompoundTag row = kidnapping(captive, RestraintType.CUFFS, true).save();
        row.remove("custodyId");
        row.remove("generation");
        CompoundTag custody = new CompoundTag();
        custody.put(captive.toString(), row);
        tag.put("custody", custody);
        return tag;
    }

    /**
     * The reconciliation has an executable call site, and it runs exactly once per world.
     *
     * <p>{@code CrimeWorldData.get} calls {@link CrimeWorldData#reconcilePhysicalState(long)} every
     * time it hands the store out, which is constantly. Every assertion below is about that being
     * safe: the first call converts, every later call in the session does nothing, and a call after a
     * save and reload — which is what a login is — does nothing either, because the marker is durable.
     *
     * <p>Without a call site the conversion would never happen at all, and after the legacy engine is
     * removed the old {@code RestraintType} on a custody row is only reachable through this path.
     */
    @Test
    void theLoadPathReconcilesExactlyOnceAndMintsNoExtraItem() {
        UUID captive = UUID.randomUUID();
        CompoundTag saved = schema14(captive);

        CrimeWorldData first = CrimeWorldData.load(saved);
        assertTrue(first.physicalRestraints().isEmpty(),
                "the pure migration invents no gear; only the reconciliation does");

        first.reconcilePhysicalState(1000L);
        AppliedRestraint afterFirst = arms(first, captive);
        assertEquals(1, first.physicalRestraints().size());
        assertEquals(CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL, first.reconciledSchema());

        // Called again in the same session, the way every CrimeWorldData.get would.
        first.reconcilePhysicalState(1001L);
        first.reconcilePhysicalState(1002L);
        assertEquals(1, first.physicalRestraints().size());
        assertEquals(afterFirst, arms(first, captive), "the same instance, not a second pair of cuffs");

        // And after a save and reload, which is the login the specification calls out.
        CrimeWorldData reloaded = CrimeWorldData.load(first.save(new CompoundTag()));
        reloaded.reconcilePhysicalState(2000L);
        assertEquals(1, reloaded.physicalRestraints().size());
        assertEquals(afterFirst, arms(reloaded, captive));
        assertEquals(1, reloaded.tethersForSubject(captive).size());
    }

    @Test
    void theLoadPathLeavesAQuarantinedStoreAlone() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        tag.put("ledger", new ListTag());
        CrimeWorldData data = CrimeWorldData.load(tag);

        data.reconcilePhysicalState(1000L);

        assertTrue(data.physicalRestraints().isEmpty());
        assertEquals(0, data.reconciledSchema(),
                "nothing was converted, so nothing may claim it was");
    }

    // ------------------------------------------------------------------ the arrest-phase pass

    @Test
    void anArrestedSubjectWithNoGearGetsOneSystemIssuedPair() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        CustodyRecord record = arrest(captive, RestraintType.NONE, true);
        data.putCustody(record);

        assertTrue(RestraintMigrationReconciler.reconcileArrestPhase(data, captive, true, 1000L));

        AppliedRestraint applied = arms(data, captive);
        assertEquals(RestraintDefinitions.HANDCUFFS_ARMS, applied.definitionId());
        assertEquals(AppliedRestraint.Provenance.SYSTEM_ISSUED, applied.provenance());
        assertEquals(AppliedRestraint.ReturnPolicy.NONE, applied.returnPolicy(),
                "the server minted these; release owes nobody an item");
        assertEquals(record.getCustodyId(), applied.custodyId());
    }

    @Test
    void theArrestPhasePassIsSafeToRunOnEveryLogin() {
        CrimeWorldData data = store();
        UUID captive = UUID.randomUUID();
        data.putCustody(arrest(captive, RestraintType.NONE, true));

        assertTrue(RestraintMigrationReconciler.reconcileArrestPhase(data, captive, true, 1000L));
        AppliedRestraint first = arms(data, captive);

        assertFalse(RestraintMigrationReconciler.reconcileArrestPhase(data, captive, true, 2000L));
        assertEquals(first, arms(data, captive), "a second login would be a second pair of cuffs");
        assertFalse(RestraintMigrationReconciler.reconcileArrestPhase(data, captive, false, 3000L),
                "a subject who is not in the restrained phase gets nothing");
    }

    @Test
    void theOldEnumMapsOntoTheDefinitionsTheSpecificationNames() {
        assertEquals(RestraintDefinitions.DUCK_TAPE_ARMS,
                RestraintMigrationReconciler.definitionFor(RestraintType.ROPE).orElseThrow());
        assertEquals(RestraintDefinitions.SHACKLES_ARMS,
                RestraintMigrationReconciler.definitionFor(RestraintType.CUFFS).orElseThrow());
        assertEquals(RestraintDefinitions.HANDCUFFS_ARMS,
                RestraintMigrationReconciler.definitionFor(RestraintType.LOCKED_CUFFS).orElseThrow());
        assertTrue(RestraintMigrationReconciler.definitionFor(RestraintType.NONE).isEmpty());
        assertTrue(RestraintMigrationReconciler.definitionFor(null).isEmpty());
    }
}
