package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.detention.DetentionKind;
import dev.otectus.mcacrime.detention.DetentionRecord;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every mutator on the schema-15 tables refuses a store this build never parsed.
 *
 * <p>The guard in {@code CrimeWorldData} is per method and nothing mechanically enforces that a new
 * one remembers it, which is exactly the kind of omission that is invisible until an operator's
 * downgraded server quietly overwrites a newer save. So the check is behavioural: put the store into
 * the from-the-future state, call every mutator reflectively, and assert that nothing moved.
 *
 * <p>Reflective rather than a hand-written list because a hand-written list is the thing that gets
 * forgotten. {@link #everyNewMutatorIsExercised()} fails if a mutator is added to one of these tables
 * without an argument recipe here.
 */
class FrozenGuardCoverageTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-00000000f00d");

    /** The mutators these tables expose, with an argument that would change something if it ran. */
    private static Map<String, Object[]> recipes() {
        Map<String, Object[]> recipes = new LinkedHashMap<>();
        recipes.put("putPhysicalRestraint",
                new Object[]{PhysicalRestraintState.empty(SUBJECT, true, OVERWORLD)});
        recipes.put("removePhysicalRestraint", new Object[]{SUBJECT});
        recipes.put("putTether", new Object[]{TetherRecord.toAnchor(UUID.randomUUID(), SUBJECT,
                TetherKind.ANCHOR, OVERWORLD, BlockPos.ZERO, 4.0D, null, false)});
        recipes.put("removeTether", new Object[]{UUID.randomUUID()});
        recipes.put("putDetention", new Object[]{DetentionRecord.of(UUID.randomUUID(), SUBJECT,
                DetentionKind.PILLORY, OVERWORLD, BlockPos.ZERO, 1L, "bent")});
        recipes.put("removeDetention", new Object[]{UUID.randomUUID()});
        recipes.put("putLock", new Object[]{LockRecord.of(UUID.randomUUID(),
                LockTarget.block(OVERWORLD, BlockPos.ZERO), null)});
        recipes.put("removeLock", new Object[]{UUID.randomUUID()});
        recipes.put("markReconciled", new Object[]{CrimeDataMigrations.SCHEMA_CUFFED_PHYSICAL});
        recipes.put("archive", new Object[]{"legacyCuffCombinations", SUBJECT.toString(),
                new ByteArrayTag(new byte[]{1})});
        return recipes;
    }

    private static CrimeWorldData frozenStore() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        tag.put("ledger", new ListTag());
        CrimeWorldData data = CrimeWorldData.load(tag);
        assertTrue(data.isReadOnlyFutureData());
        assertFalse(ServerMutationGate.allows(data));
        return data;
    }

    private static Method find(String name, Object[] args) {
        for (Method method : CrimeWorldData.class.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length
                    && Modifier.isPublic(method.getModifiers())) {
                return method;
            }
        }
        throw new AssertionError("no public CrimeWorldData." + name + " taking " + args.length + " argument(s)");
    }

    @Test
    void everyPhysicalMutatorRefusesAStoreFromTheFuture() throws Exception {
        CrimeWorldData data = frozenStore();

        for (Map.Entry<String, Object[]> recipe : recipes().entrySet()) {
            Object result = find(recipe.getKey(), recipe.getValue()).invoke(data, recipe.getValue());
            if (result instanceof Boolean accepted) {
                assertFalse(accepted, recipe.getKey() + " reported success on a read-only store");
            }
        }

        assertTrue(data.physicalRestraints().isEmpty(), "a write got through to physicalRestraints");
        assertTrue(data.tethers().isEmpty(), "a write got through to tethers");
        assertTrue(data.detentions().isEmpty(), "a write got through to detentions");
        assertTrue(data.locks().isEmpty(), "a write got through to locks");
        assertEquals(0, data.reconciledSchema(), "the reconciliation marker was stamped on a frozen store");
        assertTrue(data.archived("legacyCuffCombinations").isEmpty());
    }

    @Test
    void aFrozenStoreIsStillHandedBackByteForByte() {
        CompoundTag original = new CompoundTag();
        original.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        original.put("ledger", new ListTag());
        CompoundTag invented = new CompoundTag();
        invented.putString("shape", "something 0.8.0 added");
        original.put("restraintProfiles", invented);

        CompoundTag written = CrimeWorldData.load(original.copy()).save(new CompoundTag());

        assertEquals(original, written);
    }

    /**
     * Fails when a mutator lands on one of these tables with no recipe above.
     *
     * <p>The point of the reflection: a new {@code putSomething} on a schema-15 table that forgets
     * {@code frozen()} is caught by the test above only if this test insists it be listed.
     */
    @Test
    void everyNewMutatorIsExercised() {
        List<String> tables = List.of("PhysicalRestraint", "Tether", "Detention", "Lock");
        Map<String, Object[]> recipes = recipes();
        List<String> unlisted = new ArrayList<>();

        for (Method method : CrimeWorldData.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            String name = method.getName();
            boolean mutator = name.startsWith("put") || name.startsWith("remove");
            if (!mutator) {
                continue;
            }
            boolean ours = tables.stream().anyMatch(table -> name.endsWith(table));
            if (ours && !recipes.containsKey(name)) {
                unlisted.add(name);
            }
        }

        assertTrue(unlisted.isEmpty(), "these schema-15 mutators are not covered by the frozen-guard "
                + "test and may not call frozen(): " + unlisted);
    }
}
