package dev.otectus.mcacrime.recipe;

import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key copying, end to end: key to clay to fired mold to key (M3.2, ledger L04).
 *
 * <p>What has to survive every step is the <em>whole</em> binding — the lock identity and the revision
 * it was cut at. A copy that kept only the identity would quietly defeat rekeying, which is the one
 * thing a lock owner has to be able to rely on.
 *
 * <p>A mold's impression and its remaining casts are two registered data components on this line
 * rather than two tags, so the value under test is a {@link KeyCraftLogic.State} — the same two facts,
 * typed.
 */
class KeyMoldTest {

    private static final ResourceLocation OVERWORLD =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static LockRecord lock() {
        return LockRecord.of(UUID.randomUUID(), LockTarget.block(OVERWORLD, new BlockPos(0, 64, 0)), null);
    }

    private static KeyCraftLogic.Input key(KeyBinding binding) {
        return new KeyCraftLogic.Input(KeyCraftLogic.Kind.KEY, KeyCraftLogic.State.ofKey(binding));
    }

    private static KeyCraftLogic.State rawMold(LockRecord lock) {
        return KeyCraftLogic.State.ofMold(KeyBinding.forLock(lock, null), 0);
    }

    @Test
    void anImpressionKeepsTheKeyAndRecordsItsBinding() {
        LockRecord lock = lock();
        KeyBinding binding = KeyBinding.forLock(lock, "Front Door");

        KeyCraftLogic.Result result = KeyCraftLogic.moldCopy(
                List.of(key(binding), KeyCraftLogic.Input.of(KeyCraftLogic.Kind.CLAY))).orElseThrow();

        assertEquals(KeyCraftLogic.Kind.RAW_MOLD, result.resultKind());
        assertEquals(1, result.resultCount());
        KeyBinding recorded = result.resultState().bound().orElseThrow();
        assertEquals(binding, recorded, "identity, revision and name all survive the impression");

        assertTrue(result.remaining().get(0).isPresent(), "the original key is handed straight back");
        assertTrue(result.remaining().get(1).isEmpty(), "the clay is consumed");
    }

    @Test
    void firingAMoldCarriesTheBindingAndStampsItsCasts() {
        LockRecord lock = lock();
        KeyCraftLogic.State raw = KeyCraftLogic.State.ofMold(KeyBinding.forLock(lock, "Cellar"), 0);

        KeyCraftLogic.State baked = KeyCraftLogic.bake(raw, 4).orElseThrow();
        assertEquals(4, baked.quality());
        assertEquals(KeyBinding.forLock(lock, "Cellar"), baked.bound().orElseThrow());

        assertTrue(KeyCraftLogic.bake(KeyCraftLogic.State.EMPTY, 4).isEmpty(),
                "firing a mold that recorded nothing produces nothing");
    }

    @Test
    void castingSpendsOneUseAndTheLastCastConsumesTheMold() {
        LockRecord lock = lock();
        KeyCraftLogic.State mold = KeyCraftLogic.bake(rawMold(lock), 4).orElseThrow();
        KeyCraftLogic.Input moldInput = new KeyCraftLogic.Input(KeyCraftLogic.Kind.BAKED_MOLD, mold);
        KeyCraftLogic.Input ingot = KeyCraftLogic.Input.of(KeyCraftLogic.Kind.INGOT);

        KeyCraftLogic.Result first = KeyCraftLogic.bakedMoldCopy(List.of(moldInput, ingot), 4)
                .orElseThrow();
        assertEquals(KeyCraftLogic.Kind.KEY, first.resultKind());
        assertEquals(KeyBinding.forLock(lock, null).lockId(),
                first.resultState().bound().orElseThrow().lockId());
        Optional<KeyCraftLogic.Input> keptMold = first.remaining().get(0);
        assertTrue(keptMold.isPresent());
        assertEquals(3, keptMold.get().state().quality());
        assertTrue(first.remaining().get(1).isEmpty(), "the ingot is consumed");

        KeyCraftLogic.Result last = KeyCraftLogic.bakedMoldCopy(List.of(moldInput, ingot), 1)
                .orElseThrow();
        assertTrue(last.remaining().get(0).isEmpty(), "a mold on its last cast is consumed");

        assertTrue(KeyCraftLogic.bakedMoldCopy(List.of(moldInput, ingot), 0).isEmpty(),
                "and a spent mold casts nothing at all");
    }

    @Test
    void aCopyMadeBeforeARekeyDoesNotOpenTheLockAfterIt() {
        LockRecord lock = lock();
        KeyCraftLogic.State mold = KeyCraftLogic.bake(rawMold(lock), 4).orElseThrow();
        KeyCraftLogic.Result cast = KeyCraftLogic.bakedMoldCopy(
                List.of(new KeyCraftLogic.Input(KeyCraftLogic.Kind.BAKED_MOLD, mold),
                        KeyCraftLogic.Input.of(KeyCraftLogic.Kind.INGOT)), 4).orElseThrow();
        KeyBinding copied = cast.resultState().bound().orElseThrow();

        assertTrue(copied.opens(lock));
        assertFalse(copied.opens(lock.rekeyed()),
                "the revision travels with the copy, so a rekey invalidates it too");
    }

    @Test
    void aBlankKeyMakesNoMoldAndTwoOfAnythingRefusesTheCraft() {
        assertTrue(KeyCraftLogic.moldCopy(List.of(KeyCraftLogic.Input.of(KeyCraftLogic.Kind.KEY),
                KeyCraftLogic.Input.of(KeyCraftLogic.Kind.CLAY))).isEmpty());
        KeyBinding binding = KeyBinding.forLock(lock(), null);
        assertTrue(KeyCraftLogic.moldCopy(List.of(key(binding), key(binding),
                KeyCraftLogic.Input.of(KeyCraftLogic.Kind.CLAY))).isEmpty());
        assertTrue(KeyCraftLogic.moldCopy(List.of(key(binding),
                KeyCraftLogic.Input.of(KeyCraftLogic.Kind.CLAY),
                KeyCraftLogic.Input.of(KeyCraftLogic.Kind.CLAY))).isEmpty());
    }
}
