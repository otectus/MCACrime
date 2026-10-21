package dev.otectus.mcacrime.recipe;

import dev.otectus.mcacrime.locks.KeyBinding;
import dev.otectus.mcacrime.locks.LockInteractions;
import dev.otectus.mcacrime.locks.LockRecord;
import dev.otectus.mcacrime.locks.LockTarget;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic.Input;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic.Kind;
import dev.otectus.mcacrime.recipe.lock.KeyCraftLogic.State;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key workflows of 0.8.0 (workstream C), on this line's typed {@link State}: the blank-mold
 * impression, names that survive a ring, the whole mold chain down to the fifth cast, and the refusal a
 * player is told for each kind of wrong key. The Forge build runs the same cases over tags; its stale-key
 * replacement reads and writes a ring stack's data components here, so it is GameTest
 * {@code replacingAStaleKeyOnARingAfterARekeyKeepsItsName} instead.
 */
class KeyWorkflowTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final Component FRONT_DOOR = Component.literal("Front door key");

    private static LockRecord lock() {
        return LockRecord.of(UUID.randomUUID(), LockTarget.block(OVERWORLD, new BlockPos(0, 64, 0)), null);
    }

    private static Input key(KeyBinding binding) {
        return new Input(Kind.KEY, State.ofKey(binding));
    }

    private static Input namedKey(KeyBinding binding, Component name) {
        return new Input(Kind.KEY, State.ofKey(binding, name));
    }

    private static Input blankMold() {
        return Input.of(Kind.RAW_MOLD);
    }

    @Test
    void aBlankMoldTakesTheKeysWholeBindingKeepsItsOwnNameAndTheKeyComesBack() {
        LockRecord lock = lock();
        KeyBinding binding = KeyBinding.forLock(lock.rekeyed(), "Cellar");
        Input mold = new Input(Kind.RAW_MOLD, State.ofMold(null, 0, Component.literal("My mold")));
        Input key = key(binding);

        KeyCraftLogic.Result result = KeyCraftLogic.moldImprint(List.of(mold, key)).orElseThrow();

        assertEquals(Kind.RAW_MOLD, result.resultKind());
        assertEquals(binding, result.resultState().bound().orElseThrow(), "identity, revision and name are recorded whole");
        assertEquals("My mold", result.resultState().name().getString(), "the mold keeps its own name");
        assertEquals(key, result.remaining().get(1).orElseThrow(), "the key comes back exactly as it went in");
        assertTrue(result.remaining().get(0).isEmpty(), "the blank mold becomes the result");
    }

    @Test
    void theImpressionRefusesARecordedMoldABakedMoldABlankKeyAndAnythingExtra() {
        KeyBinding binding = KeyBinding.forLock(lock(), null);
        Input recorded = new Input(Kind.RAW_MOLD, State.ofMold(KeyBinding.forLock(lock(), null), 0));
        assertTrue(KeyCraftLogic.moldImprint(List.of(recorded, key(binding))).isEmpty(),
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

    @Test
    void bothImpressionPathsFireAndCastFourCopiesThatStopAtTheFifthAndAfterARekey() {
        LockRecord lock = lock();
        KeyBinding original = KeyBinding.forLock(lock, "Front Door");
        for (KeyCraftLogic.Result impression : List.of(
                KeyCraftLogic.moldCopy(List.of(key(original), Input.of(Kind.CLAY))).orElseThrow(),
                KeyCraftLogic.moldImprint(List.of(blankMold(), key(original))).orElseThrow())) {
            Input mold = new Input(Kind.BAKED_MOLD, KeyCraftLogic.bake(impression.resultState(), 4).orElseThrow());
            int casts = 0;
            while (mold != null) {
                KeyCraftLogic.Result cast = KeyCraftLogic.bakedMoldCopy(List.of(mold, Input.of(Kind.INGOT)),
                        mold.state().quality()).orElseThrow();
                KeyBinding copy = cast.resultState().bound().orElseThrow();
                assertEquals(original, copy);
                assertTrue(copy.opens(lock));
                assertFalse(copy.opens(lock.rekeyed()));
                casts++;
                mold = cast.remaining().get(0).orElse(null);
            }
            assertEquals(4, casts, "a fresh mold casts four keys and the fourth uses it up");
        }
    }

    @Test
    void aRenamedKeyKeepsItsNameOnTheRingAndGetsItBackWhenTakenOff() {
        KeyBinding front = KeyBinding.forLock(lock(), "Cell Door");
        KeyBinding back = KeyBinding.forLock(lock(), "Safe");
        KeyCraftLogic.Result ring = KeyCraftLogic.ringCreate(List.of(namedKey(front, FRONT_DOOR), key(back)), 16)
                .orElseThrow();
        assertEquals(FRONT_DOOR, ring.resultState().labels().get(front.lockId()));
        assertNull(ring.resultState().labels().get(back.lockId()));

        KeyBinding gate = KeyBinding.forLock(lock(), "Gate");
        KeyCraftLogic.Result added = KeyCraftLogic.ringAdd(List.of(new Input(Kind.RING, ring.resultState()),
                namedKey(gate, Component.literal("Gate key"))), 16).orElseThrow();
        assertEquals(FRONT_DOOR, added.resultState().labels().get(front.lockId()), "adding keeps earlier names");

        KeyCraftLogic.Result off = KeyCraftLogic.ringDisassemble(List.of(new Input(Kind.RING, added.resultState())))
                .orElseThrow();
        assertEquals(gate, off.resultState().bound().orElseThrow());
        assertEquals("Gate key", off.resultState().name().getString());
        State remaining = off.remaining().get(0).orElseThrow().state();
        assertEquals(2, remaining.ring().size());
        assertEquals(Map.of(front.lockId(), FRONT_DOOR), remaining.labels(), "the removed key took its name with it");
    }

    @Test
    void aRingRefusesAFullOrDuplicateAdditionWithoutTouchingWhatItHolds() {
        KeyBinding a = KeyBinding.forLock(lock(), "A");
        KeyBinding b = KeyBinding.forLock(lock(), "B");
        State ring = KeyCraftLogic.ringCreate(List.of(key(a), key(b)), 2).orElseThrow().resultState();
        assertTrue(KeyCraftLogic.ringAdd(List.of(new Input(Kind.RING, ring), key(KeyBinding.forLock(lock(), "C"))), 2)
                .isEmpty(), "over capacity");
        assertTrue(KeyCraftLogic.ringAdd(List.of(new Input(Kind.RING, ring), key(a)), 16).isEmpty(), "duplicate lock");
        assertTrue(KeyCraftLogic.ringAdd(List.of(new Input(Kind.RING, ring), new Input(Kind.RING, ring), key(
                KeyBinding.forLock(lock(), "D"))), 16).isEmpty(), "two rings");
        assertEquals(List.of(a, b), ring.ring(), "a refused craft leaves the ring as it was");
    }

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
