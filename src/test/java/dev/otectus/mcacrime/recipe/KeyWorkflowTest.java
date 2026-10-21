package dev.otectus.mcacrime.recipe;

import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.KeyRingBindings;
import dev.otectus.mcacrime.locks.LockInteractions;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic.Input;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic.Kind;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key workflows of 0.8.0 (workstream C): the blank-mold impression, names that survive a ring, the
 * whole mold chain down to the fifth cast, and the refusal a player is told for each kind of wrong key.
 */
class KeyWorkflowTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final String FRONT_DOOR_NAME = "{\"text\":\"Front door key\"}";

    private static LockRecord lock() {
        return LockRecord.of(UUID.randomUUID(), LockTarget.block(OVERWORLD, new BlockPos(0, 64, 0)), null);
    }

    private static Input key(KeyBinding binding) {
        CompoundTag tag = new CompoundTag();
        binding.writeTo(tag);
        return new Input(Kind.KEY, tag);
    }

    private static Input namedKey(KeyBinding binding, String nameJson) {
        Input key = key(binding);
        CompoundTag display = new CompoundTag();
        display.putString("Name", nameJson);
        key.tag().put("display", display);
        return key;
    }

    private static Input blankMold() {
        return Input.of(Kind.RAW_MOLD);
    }

    // --- the blank-mold impression -------------------------------------------------------------------

    @Test
    void aBlankMoldTakesTheKeysWholeBindingAndTheKeyComesBack() {
        LockRecord lock = lock();
        KeyBinding binding = KeyBinding.forLock(lock.rekeyed(), "Cellar");
        CompoundTag moldTag = new CompoundTag();
        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"My mold\"}");
        moldTag.put("display", display);
        Input mold = new Input(Kind.RAW_MOLD, moldTag);
        Input key = key(binding);
        CompoundTag keyBefore = key.tag().copy();

        KeyCraftLogic.Result result = KeyCraftLogic.moldImprint(List.of(mold, key)).orElseThrow();

        assertEquals(Kind.RAW_MOLD, result.resultKind());
        assertEquals(binding, KeyCraftLogic.recorded(result.resultTag()).orElseThrow(),
                "identity, revision and name are recorded whole");
        assertEquals("{\"text\":\"My mold\"}", result.resultTag().getCompound("display").getString("Name"),
                "the mold keeps its own name");
        assertTrue(result.remaining().get(1).isPresent(), "the key is handed back");
        assertEquals(keyBefore, result.remaining().get(1).get().tag(), "the key comes back exactly as it went in");
        assertTrue(result.remaining().get(0).isEmpty(), "the blank mold becomes the result");
        assertEquals(keyBefore, key.tag(), "previewing the craft changes no input");
    }

    @Test
    void theImpressionRefusesARecordedMoldABakedMoldABlankKeyAndAnythingExtra() {
        KeyBinding binding = KeyBinding.forLock(lock(), null);
        CompoundTag recorded = new CompoundTag();
        recorded.put(KeyBinding.TAG_COPIED_KEY, KeyBinding.forLock(lock(), null).save());
        assertTrue(KeyCraftLogic.moldImprint(List.of(new Input(Kind.RAW_MOLD, recorded), key(binding))).isEmpty(),
                "a mold that records a key is never overwritten");
        assertTrue(KeyCraftLogic.moldImprint(List.of(Input.of(Kind.BAKED_MOLD), key(binding))).isEmpty(),
                "a baked mold takes no impression");
        assertTrue(KeyCraftLogic.moldImprint(List.of(blankMold(), Input.of(Kind.KEY))).isEmpty(),
                "a blank key has nothing to copy");
        assertTrue(KeyCraftLogic.moldImprint(List.of(blankMold(), Input.of(Kind.OTHER))).isEmpty(),
                "a foreign key has nothing to copy");
        assertTrue(KeyCraftLogic.moldImprint(List.of(blankMold(), key(binding), Input.of(Kind.CLAY))).isEmpty(),
                "a third ingredient refuses the craft rather than being eaten");
        assertTrue(KeyCraftLogic.moldImprint(List.of(blankMold(), blankMold(), key(binding))).isEmpty());
    }

    @Test
    void theImpressionAndTheClayRecipeNeverMatchTheSameGrid() {
        KeyBinding binding = KeyBinding.forLock(lock(), null);
        assertTrue(KeyCraftLogic.moldCopy(List.of(blankMold(), key(binding))).isEmpty());
        assertTrue(KeyCraftLogic.moldImprint(List.of(key(binding), Input.of(Kind.CLAY))).isEmpty());
        assertTrue(KeyCraftLogic.ringDisassemble(List.of(blankMold())).isEmpty());
    }

    // --- the whole chain -----------------------------------------------------------------------------

    @Test
    void bothImpressionPathsFireAndCastFourCopiesThatStopAtTheFifthAndAfterARekey() {
        LockRecord lock = lock();
        KeyBinding original = KeyBinding.forLock(lock, "Front Door");
        for (KeyCraftLogic.Result impression : List.of(
                KeyCraftLogic.moldCopy(List.of(key(original), Input.of(Kind.CLAY))).orElseThrow(),
                KeyCraftLogic.moldImprint(List.of(blankMold(), key(original))).orElseThrow())) {
            CompoundTag baked = KeyCraftLogic.bake(impression.resultTag(), 4).orElseThrow();
            Input mold = new Input(Kind.BAKED_MOLD, baked);
            int casts = 0;
            for (int quality = 4; quality >= 1; quality--) {
                KeyCraftLogic.Result cast = KeyCraftLogic.bakedMoldCopy(List.of(mold, Input.of(Kind.INGOT)),
                        mold.tag().getInt("Quality")).orElseThrow();
                KeyBinding copy = KeyBinding.read(cast.resultTag()).orElseThrow();
                assertEquals(original, copy, "every copy carries the whole binding");
                assertTrue(copy.opens(lock), "a copy opens the original lock");
                assertFalse(copy.opens(lock.rekeyed()), "and stops once the lock is rekeyed");
                casts++;
                if (cast.remaining().get(0).isEmpty()) {
                    mold = null;
                    break;
                }
                mold = cast.remaining().get(0).get();
            }
            assertEquals(4, casts, "a fresh mold casts four keys");
            assertNull(mold, "the fourth cast uses the mold up, so there is no fifth");
        }
    }

    // --- rings keep names ---------------------------------------------------------------------------

    @Test
    void aRenamedKeyKeepsItsNameOnTheRingAndGetsItBackWhenTakenOff() {
        KeyBinding front = KeyBinding.forLock(lock(), "Cell Door");
        KeyBinding back = KeyBinding.forLock(lock(), "Safe");
        KeyCraftLogic.Result ring = KeyCraftLogic.ringCreate(
                List.of(namedKey(front, FRONT_DOOR_NAME), key(back)), 16).orElseThrow();
        List<KeyRingBindings.Entry> entries = KeyRingBindings.entries(ring.resultTag());
        assertEquals(FRONT_DOOR_NAME, entries.get(0).customName());
        assertNull(entries.get(1).customName());

        KeyBinding third = KeyBinding.forLock(lock(), "Gate");
        KeyCraftLogic.Result added = KeyCraftLogic.ringAdd(List.of(
                new Input(Kind.RING, ring.resultTag()), namedKey(third, "{\"text\":\"Gate key\"}")), 16).orElseThrow();
        assertEquals(FRONT_DOOR_NAME, KeyRingBindings.entries(added.resultTag()).get(0).customName(),
                "adding a key never strips another key's name");

        KeyCraftLogic.Result off = KeyCraftLogic.ringDisassemble(List.of(new Input(Kind.RING, added.resultTag())))
                .orElseThrow();
        assertEquals("{\"text\":\"Gate key\"}", off.resultTag().getCompound("display").getString("Name"));
        assertEquals(third, KeyBinding.read(off.resultTag()).orElseThrow());
        CompoundTag remainingRing = off.remaining().get(0).orElseThrow().tag();
        assertEquals(2, KeyRingBindings.count(remainingRing));
        assertEquals(FRONT_DOOR_NAME, KeyRingBindings.entries(remainingRing).get(0).customName());
    }

    @Test
    void replacingAStaleKeyAfterARekeyKeepsItsName() {
        LockRecord lock = lock();
        KeyCraftLogic.Result ring = KeyCraftLogic.ringCreate(List.of(
                namedKey(KeyBinding.forLock(lock, "Cell Door"), FRONT_DOOR_NAME),
                key(KeyBinding.forLock(lock(), "Safe"))), 16).orElseThrow();
        CompoundTag tag = ring.resultTag().copy();
        assertTrue(KeyRingBindings.replace(tag, KeyBinding.forLock(lock.rekeyed(), "Cell Door")));
        KeyRingBindings.Entry replaced = KeyRingBindings.entries(tag).get(0);
        assertEquals(lock.rekeyed().bindingRevision(), replaced.binding().bindingRevision());
        assertEquals(FRONT_DOOR_NAME, replaced.customName());
    }

    @Test
    void aRingRefusesAFullOrDuplicateAdditionWithoutTouchingWhatItHolds() {
        KeyBinding a = KeyBinding.forLock(lock(), "A");
        KeyBinding b = KeyBinding.forLock(lock(), "B");
        CompoundTag ring = KeyCraftLogic.ringCreate(List.of(key(a), key(b)), 2).orElseThrow().resultTag();
        assertTrue(KeyCraftLogic.ringAdd(List.of(new Input(Kind.RING, ring), key(KeyBinding.forLock(lock(), "C"))), 2)
                .isEmpty(), "over capacity");
        assertTrue(KeyCraftLogic.ringAdd(List.of(new Input(Kind.RING, ring), key(a)), 16).isEmpty(), "duplicate lock");
        assertTrue(KeyCraftLogic.ringAdd(List.of(new Input(Kind.RING, ring), new Input(Kind.RING, ring), key(
                KeyBinding.forLock(lock(), "D"))), 16).isEmpty(), "two rings");
        assertEquals(2, KeyRingBindings.count(ring), "a refused craft leaves the ring as it was");
    }

    // --- refusals ------------------------------------------------------------------------------------

    @Test
    void eachKindOfWrongKeyIsToldApartWhetherOrNotTheLockIsLocked() {
        LockRecord lock = lock();
        LockRecord unlocked = lock.locked(false);
        LockRecord locked = lock.locked(true);
        KeyBinding other = KeyBinding.forLock(lock(), null);
        KeyBinding stale = KeyBinding.forLock(lock, null);
        for (LockRecord state : List.of(unlocked, locked.rekeyed().locked(false), locked)) {
            assertEquals("mcacrime.lock.wrong_key", LockInteractions.refusalKey(state, other, false),
                    "another lock's key is a wrong key, never Unlocked");
            assertEquals("mcacrime.lock.ring_no_key", LockInteractions.refusalKey(state, null, true));
        }
        assertEquals("mcacrime.lock.rekeyed", LockInteractions.refusalKey(unlocked.rekeyed(), stale, false),
                "an old copy of a rekeyed lock says so even while the lock is open");
        assertEquals("mcacrime.lock.rekeyed", LockInteractions.refusalKey(locked.rekeyed(), stale, true));
    }
}
