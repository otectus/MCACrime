package dev.otectus.mcacrime.recipe;

import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
 */
class KeyMoldTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private static LockRecord lock() {
        return LockRecord.of(UUID.randomUUID(), LockTarget.block(OVERWORLD, new BlockPos(0, 64, 0)), null);
    }

    private static KeyCraftLogic.Input key(KeyBinding binding) {
        CompoundTag tag = new CompoundTag();
        binding.writeTo(tag);
        return new KeyCraftLogic.Input(KeyCraftLogic.Kind.KEY, tag);
    }

    @Test
    void anImpressionKeepsTheKeyAndRecordsItsBinding() {
        LockRecord lock = lock();
        KeyBinding binding = KeyBinding.forLock(lock, "Front Door");

        KeyCraftLogic.Result result = KeyCraftLogic.moldCopy(
                List.of(key(binding), KeyCraftLogic.Input.of(KeyCraftLogic.Kind.CLAY))).orElseThrow();

        assertEquals(KeyCraftLogic.Kind.RAW_MOLD, result.resultKind());
        assertEquals(1, result.resultCount());
        KeyBinding recorded = KeyCraftLogic.recorded(result.resultTag()).orElseThrow();
        assertEquals(binding, recorded, "identity, revision and name all survive the impression");

        assertTrue(result.remaining().get(0).isPresent(), "the original key is handed straight back");
        assertTrue(result.remaining().get(1).isEmpty(), "the clay is consumed");
    }

    @Test
    void firingAMoldCarriesTheBindingAndStampsItsCasts() {
        LockRecord lock = lock();
        CompoundTag raw = new CompoundTag();
        raw.put(KeyBinding.TAG_COPIED_KEY, KeyBinding.forLock(lock, "Cellar").save());

        CompoundTag baked = KeyCraftLogic.bake(raw, 4).orElseThrow();
        assertEquals(4, baked.getInt("Quality"));
        assertEquals(KeyBinding.forLock(lock, "Cellar"), KeyCraftLogic.recorded(baked).orElseThrow());

        assertTrue(KeyCraftLogic.bake(new CompoundTag(), 4).isEmpty(),
                "firing a mold that recorded nothing produces nothing");
    }

    @Test
    void castingSpendsOneUseAndTheLastCastConsumesTheMold() {
        LockRecord lock = lock();
        CompoundTag mold = KeyCraftLogic.bake(recorded(lock), 4).orElseThrow();
        KeyCraftLogic.Input moldInput = new KeyCraftLogic.Input(KeyCraftLogic.Kind.BAKED_MOLD, mold);
        KeyCraftLogic.Input ingot = KeyCraftLogic.Input.of(KeyCraftLogic.Kind.INGOT);

        KeyCraftLogic.Result first = KeyCraftLogic.bakedMoldCopy(List.of(moldInput, ingot), 4)
                .orElseThrow();
        assertEquals(KeyCraftLogic.Kind.KEY, first.resultKind());
        assertEquals(KeyBinding.forLock(lock, null).lockId(),
                KeyBinding.read(first.resultTag()).orElseThrow().lockId());
        Optional<KeyCraftLogic.Input> keptMold = first.remaining().get(0);
        assertTrue(keptMold.isPresent());
        assertEquals(3, keptMold.get().tag().getInt("Quality"));
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
        CompoundTag mold = KeyCraftLogic.bake(recorded(lock), 4).orElseThrow();
        KeyCraftLogic.Result cast = KeyCraftLogic.bakedMoldCopy(
                List.of(new KeyCraftLogic.Input(KeyCraftLogic.Kind.BAKED_MOLD, mold),
                        KeyCraftLogic.Input.of(KeyCraftLogic.Kind.INGOT)), 4).orElseThrow();
        KeyBinding copied = KeyBinding.read(cast.resultTag()).orElseThrow();

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

    private static CompoundTag recorded(LockRecord lock) {
        CompoundTag raw = new CompoundTag();
        raw.put(KeyBinding.TAG_COPIED_KEY, KeyBinding.forLock(lock, null).save());
        return raw;
    }
}
