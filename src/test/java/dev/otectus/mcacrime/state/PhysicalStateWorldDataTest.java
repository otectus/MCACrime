package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.detention.DetentionKind;
import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.restraint.AppliedRestraint;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.restraint.RestraintApplier;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import dev.otectus.mcacrime.restraint.RestraintSlot;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherRecord;
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

/** The five schema-15 world tables: round trip, isolation, malformed rows and forward compatibility. */
class PhysicalStateWorldDataTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static CrimeWorldData store() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA);
        tag.put("ledger", new ListTag());
        return CrimeWorldData.load(tag, RegistryAccess.EMPTY);
    }

    private static AppliedRestraint cuffs(long appliedTick) {
        return AppliedRestraint.of(
                RestraintDefinitions.get(RestraintDefinitions.HANDCUFFS_ARMS).orElseThrow(), null,
                RestraintApplier.system(), AppliedRestraint.ApplicationContext.LAWFUL,
                AppliedRestraint.Provenance.SYSTEM_ISSUED, AppliedRestraint.ReturnPolicy.NONE, null,
                appliedTick);
    }

    @Test
    void aRestrainedSubjectSurvivesSaveAndLoad() {
        CrimeWorldData data = store();
        UUID subject = UUID.randomUUID();
        PhysicalRestraintState state = PhysicalRestraintState.empty(subject, true, OVERWORLD)
                .with(RestraintSlot.ARMS, cuffs(100L))
                .with(RestraintSlot.LEGS, AppliedRestraint.of(
                        RestraintDefinitions.get(RestraintDefinitions.SHACKLES_LEGS).orElseThrow(), null,
                        RestraintApplier.system(), AppliedRestraint.ApplicationContext.LAWFUL,
                        AppliedRestraint.Provenance.SYSTEM_ISSUED, AppliedRestraint.ReturnPolicy.NONE,
                        null, 100L));
        assertTrue(data.putPhysicalRestraint(state));

        CrimeWorldData reloaded = CrimeWorldData.load(data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        assertEquals(List.of(state), reloaded.physicalRestraints());
        PhysicalRestraintState back = reloaded.physicalRestraint(subject);
        assertNotNull(back);
        assertEquals(state.slot(RestraintSlot.ARMS), back.slot(RestraintSlot.ARMS));
        assertEquals(state.slot(RestraintSlot.LEGS), back.slot(RestraintSlot.LEGS));
        assertTrue(back.slot(RestraintSlot.HEAD).isEmpty());
        assertEquals(state.revision(), back.revision());
        assertEquals(state.generation(), back.generation());
    }

    /** The two-subject isolation test §3.2 asks for: nothing bleeds through the save file. */
    @Test
    void twoSubjectsWearingTheSameDefinitionStayIndependent() {
        CrimeWorldData data = store();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AppliedRestraint firstCuffs = cuffs(10L);
        AppliedRestraint secondCuffs = cuffs(20L).damaged(9);
        data.putPhysicalRestraint(PhysicalRestraintState.empty(first, true, OVERWORLD)
                .with(RestraintSlot.ARMS, firstCuffs));
        data.putPhysicalRestraint(PhysicalRestraintState.empty(second, false, OVERWORLD)
                .with(RestraintSlot.ARMS, secondCuffs));

        CrimeWorldData reloaded = CrimeWorldData.load(data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        AppliedRestraint firstBack = reloaded.physicalRestraint(first).slot(RestraintSlot.ARMS).orElseThrow();
        AppliedRestraint secondBack = reloaded.physicalRestraint(second).slot(RestraintSlot.ARMS).orElseThrow();
        assertEquals(firstCuffs.remainingDurability(), firstBack.remainingDurability());
        assertEquals(secondCuffs.remainingDurability(), secondBack.remainingDurability());
        assertFalse(firstBack.instanceId().equals(secondBack.instanceId()));
        assertTrue(reloaded.physicalRestraint(first).subjectIsPlayer());
        assertFalse(reloaded.physicalRestraint(second).subjectIsPlayer(),
                "one table holds players and villagers, and the flag is what tells them apart");
    }

    @Test
    void theOtherFourTablesRoundTrip() {
        CrimeWorldData data = store();
        UUID subject = UUID.randomUUID();
        TetherRecord tether = TetherRecord.toAnchor(UUID.randomUUID(), subject, TetherKind.ANCHOR,
                OVERWORLD, new BlockPos(1, 64, 1), 4.0D, null, false);
        DetentionRecord detention = DetentionRecord.of(UUID.randomUUID(), subject, DetentionKind.PILLORY,
                OVERWORLD, new BlockPos(2, 64, 2), 1L, "bent");
        LockRecord lock = LockRecord.of(UUID.randomUUID(),
                LockTarget.block(OVERWORLD, new BlockPos(3, 64, 3)), UUID.randomUUID());

        assertTrue(data.putTether(tether));
        assertTrue(data.putDetention(detention));
        assertTrue(data.putLock(lock));

        CrimeWorldData reloaded = CrimeWorldData.load(data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        assertEquals(List.of(tether), reloaded.tethers());
        assertEquals(List.of(detention), reloaded.detentions());
        assertEquals(List.of(lock), reloaded.locks());
        assertEquals(tether, reloaded.tether(tether.id()));
        assertEquals(detention, reloaded.detentionForSubject(subject));
        assertEquals(lock, reloaded.lock(lock.lockId()));
        assertEquals(List.of(tether), reloaded.tethersForSubject(subject));
    }

    @Test
    void removalIsByKeyAndReportsWhetherAnythingWasThere() {
        CrimeWorldData data = store();
        UUID subject = UUID.randomUUID();
        data.putPhysicalRestraint(PhysicalRestraintState.empty(subject, true, OVERWORLD)
                .with(RestraintSlot.ARMS, cuffs(0L)));

        assertTrue(data.removePhysicalRestraint(subject));
        assertFalse(data.removePhysicalRestraint(subject));
        assertNull(data.physicalRestraint(subject));
        assertFalse(data.removeTether(null));
        assertFalse(data.removeLock(UUID.randomUUID()));
        assertFalse(data.removeDetention(UUID.randomUUID()));
    }

    @Test
    void anUnreadableRowIsQuarantinedRatherThanDropped() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA);
        tag.put("ledger", new ListTag());
        ListTag rows = new ListTag();
        CompoundTag broken = new CompoundTag();
        broken.putString("kind", "chain"); // no id, no subject
        rows.add(broken);
        tag.put("tethers", rows);

        CrimeWorldData data = CrimeWorldData.load(tag, RegistryAccess.EMPTY);

        assertTrue(data.tethers().isEmpty());
        assertEquals(1, data.quarantineCount(),
                "a hold nobody can parse is still the reason somebody could not walk away");
    }

    /** Forward compatibility: a shape this build does not understand is kept, not guessed at. */
    @Test
    void anUnrecognisedTableShapeIsReEmittedVerbatim() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA);
        tag.put("ledger", new ListTag());
        CompoundTag futureShape = new CompoundTag();
        futureShape.putString("shape", "something 0.8.0 added");
        tag.put("tethers", futureShape);

        CompoundTag written = CrimeWorldData.load(tag, RegistryAccess.EMPTY).save(new CompoundTag(), RegistryAccess.EMPTY);

        assertEquals(futureShape, written.getCompound("tethers"),
                "a newer jar's table was rewritten in this build's shape, losing it");
        assertFalse(written.contains("tethers", Tag.TAG_LIST));
    }

    @Test
    void theArchiveSlotKeepsWhatIsFiledInItAcrossSaveAndLoad() {
        CrimeWorldData data = store();
        UUID subject = UUID.randomUUID();

        assertTrue(data.archive("legacyCuffCombinations", subject.toString(),
                new net.minecraft.nbt.ByteArrayTag(new byte[]{1, 2, 3})));

        CrimeWorldData reloaded = CrimeWorldData.load(data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);

        CompoundTag section = reloaded.archived("legacyCuffCombinations");
        assertTrue(section.contains(subject.toString()));
        assertEquals(3, section.getByteArray(subject.toString()).length);
        assertTrue(reloaded.archived("nothing filed here").isEmpty());
    }

    @Test
    void theReconciliationMarkerSurvivesAndNeverGoesBackwards() {
        CrimeWorldData data = store();
        assertEquals(0, data.reconciledSchema());

        assertTrue(data.markReconciled(15));
        assertFalse(data.markReconciled(15), "stamping it twice is not a change");
        assertFalse(data.markReconciled(14), "a marker may never go backwards");

        assertEquals(15, CrimeWorldData.load(data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY).reconciledSchema());
    }

    @Test
    void anEmptyStoreWritesTheFiveTablesAsEmptyListsAndNoMarker() {
        CompoundTag written = store().save(new CompoundTag(), RegistryAccess.EMPTY);

        assertTrue(written.contains("physicalRestraints", Tag.TAG_LIST));
        assertEquals(0, written.getList("physicalRestraints", Tag.TAG_COMPOUND).size());
        assertTrue(written.contains("tethers", Tag.TAG_LIST));
        assertTrue(written.contains("detentions", Tag.TAG_LIST));
        assertTrue(written.contains("locks", Tag.TAG_LIST));
        assertFalse(written.contains("reconciledSchema"),
                "absent already reads as 'the reconciliation has not run'");
    }
}
