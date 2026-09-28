package dev.otectus.mcacrime.recipe;

import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.KeyRingBindings;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key rings: exact input and output counts, and the two losses the specification forbids (M3.2).
 *
 * <p>"Key-ring recipes must reject ambiguous multiple-ring combinations or process every input without
 * losing keys. Do not consume several rings and return one key." Both halves are asserted here, and so
 * is the capacity rule that matters most in practice: <em>lowering</em> the configured capacity must
 * refuse additions, never erase what a ring already carries.
 */
class KeyRingRecipeTest {

    private static final int CAPACITY = 16;

    private static KeyCraftLogic.Input key(UUID lockId, String name) {
        CompoundTag tag = new CompoundTag();
        new KeyBinding(lockId, 1L, name).writeTo(tag);
        return new KeyCraftLogic.Input(KeyCraftLogic.Kind.KEY, tag);
    }

    private static KeyCraftLogic.Input blankKey() {
        return KeyCraftLogic.Input.of(KeyCraftLogic.Kind.KEY);
    }

    private static KeyCraftLogic.Input ring(List<KeyBinding> bindings) {
        CompoundTag tag = new CompoundTag();
        KeyRingBindings.write(tag, bindings);
        return new KeyCraftLogic.Input(KeyCraftLogic.Kind.RING, tag);
    }

    @Test
    void copyThroughMoldsAndRingsThenDisassemble() {
        UUID frontDoor = UUID.randomUUID();
        UUID cellar = UUID.randomUUID();

        // Two bound keys make one ring holding both. Two in, one out, nothing left behind.
        KeyCraftLogic.Result created = KeyCraftLogic
                .ringCreate(List.of(key(frontDoor, "Front Door"), key(cellar, "Cellar")), CAPACITY)
                .orElseThrow();
        assertEquals(KeyCraftLogic.Kind.RING, created.resultKind());
        assertEquals(1, created.resultCount());
        assertEquals(2, KeyRingBindings.count(created.resultTag()));
        assertEquals(2, created.remaining().size());
        assertTrue(created.remaining().stream().allMatch(Optional::isEmpty), "both keys are consumed");

        // A third key joins the same ring.
        UUID shed = UUID.randomUUID();
        KeyCraftLogic.Result added = KeyCraftLogic.ringAdd(
                List.of(new KeyCraftLogic.Input(KeyCraftLogic.Kind.RING, created.resultTag()),
                        key(shed, "Shed")), CAPACITY).orElseThrow();
        assertEquals(3, KeyRingBindings.count(added.resultTag()));

        // Disassembly gives back the last key added, and the ring keeps the other two.
        KeyCraftLogic.Result taken = KeyCraftLogic.ringDisassemble(
                List.of(new KeyCraftLogic.Input(KeyCraftLogic.Kind.RING, added.resultTag())))
                .orElseThrow();
        assertEquals(KeyCraftLogic.Kind.KEY, taken.resultKind());
        assertEquals(1, taken.resultCount());
        KeyBinding recovered = KeyBinding.read(taken.resultTag()).orElseThrow();
        assertEquals(shed, recovered.lockId(), "the key that comes back is the one that went on last");
        assertEquals("Shed", recovered.displayName().orElseThrow(), "and it keeps its name");

        CompoundTag keptRing = taken.remaining().get(0).orElseThrow().tag();
        assertEquals(2, KeyRingBindings.count(keptRing));
        assertTrue(KeyRingBindings.holds(keptRing, frontDoor));
        assertTrue(KeyRingBindings.holds(keptRing, cellar));
        assertFalse(KeyRingBindings.holds(keptRing, shed));
    }

    @Test
    void multipleRingsInOneRecipe() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        KeyCraftLogic.Input first = ring(List.of(KeyBinding.of(a, 1L)));
        KeyCraftLogic.Input second = ring(List.of(KeyBinding.of(b, 1L)));

        assertTrue(KeyCraftLogic.ringAdd(List.of(first, second, key(UUID.randomUUID(), null)), CAPACITY)
                .isEmpty(), "two rings in one grid is ambiguous and is refused outright");
        assertTrue(KeyCraftLogic.ringDisassemble(List.of(first, second)).isEmpty(),
                "and several rings can never become one key");
        assertTrue(KeyCraftLogic.ringCreate(List.of(first, second), CAPACITY).isEmpty());
    }

    @Test
    void capacityReductionRefusesAdditionsWithoutErasingKeys() {
        List<KeyBinding> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(KeyBinding.of(UUID.randomUUID(), 1L));
        }
        KeyCraftLogic.Input full = ring(eight);

        // The operator lowers locks.maxKeysPerRing to four. The ring keeps all eight keys.
        assertEquals(8, KeyRingBindings.count(full.tag()));
        assertTrue(KeyCraftLogic.ringAdd(List.of(full, key(UUID.randomUUID(), null)), 4).isEmpty(),
                "no more keys fit");
        assertEquals(8, KeyRingBindings.count(full.tag()), "and not one of the eight is lost");

        // Disassembly still works, so an over-full ring can be brought back within the limit.
        KeyCraftLogic.Result taken = KeyCraftLogic.ringDisassemble(List.of(full)).orElseThrow();
        assertEquals(7, KeyRingBindings.count(taken.remaining().get(0).orElseThrow().tag()));
    }

    @Test
    void aBlankKeyOrADuplicateIsRefusedRatherThanWastingASlot() {
        UUID lock = UUID.randomUUID();
        assertTrue(KeyCraftLogic.ringCreate(List.of(key(lock, "A"), blankKey()), CAPACITY).isEmpty(),
                "a blank key on a ring is a slot nobody can use");
        assertTrue(KeyCraftLogic.ringCreate(List.of(key(lock, "A"), key(lock, "A")), CAPACITY).isEmpty(),
                "and a second copy of the same key takes a slot from one that opens something");

        KeyCraftLogic.Input existing = ring(List.of(KeyBinding.of(lock, 1L)));
        assertTrue(KeyCraftLogic.ringAdd(List.of(existing, key(lock, "A")), CAPACITY).isEmpty());
    }

    @Test
    void anythingElseInTheGridRefusesTheCraft() {
        assertTrue(KeyCraftLogic.ringCreate(
                List.of(key(UUID.randomUUID(), null), KeyCraftLogic.Input.of(KeyCraftLogic.Kind.OTHER)),
                CAPACITY).isEmpty());
        assertTrue(KeyCraftLogic.ringAdd(
                List.of(ring(List.of(KeyBinding.of(UUID.randomUUID(), 1L))),
                        KeyCraftLogic.Input.of(KeyCraftLogic.Kind.INGOT)), CAPACITY).isEmpty());
        assertTrue(KeyCraftLogic.ringDisassemble(
                List.of(ring(List.of(KeyBinding.of(UUID.randomUUID(), 1L))),
                        KeyCraftLogic.Input.of(KeyCraftLogic.Kind.CLAY))).isEmpty());
    }

    @Test
    void anEmptyRingHasNothingToGiveBack() {
        assertTrue(KeyCraftLogic.ringDisassemble(List.of(ring(List.of()))).isEmpty());
        assertTrue(KeyCraftLogic.ringCreate(List.of(key(UUID.randomUUID(), null)), CAPACITY).isEmpty(),
                "one key is not a ring");
        assertTrue(KeyCraftLogic.ringCreate(List.of(), CAPACITY).isEmpty());
        assertTrue(KeyCraftLogic.ringAdd(null, CAPACITY).isEmpty());
    }
}
