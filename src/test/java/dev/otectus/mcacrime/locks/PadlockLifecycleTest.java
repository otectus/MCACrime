package dev.otectus.mcacrime.locks;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A padlock's whole life in the lock table: placed, bound, rekeyed, detached, recovered (M3.4).
 *
 * <p>Two rules matter more than the rest. Detaching is <b>idempotent</b>, because a block broken by a
 * player, an explosion and a piston in the same tick must detach one padlock and drop one item —
 * upstream's chain equivalent duplicates on exactly that path. And a detached lock <b>keeps its row</b>,
 * because the keys in players' pockets are still bound to it and an operator has to be able to find
 * out what they open.
 */
class PadlockLifecycleTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private CrimeWorldData data;

    @BeforeEach
    void freshWorld() {
        data = new CrimeWorldData();
        LockService.invalidate();
    }

    private static LockTarget at(int x, int y, int z) {
        return LockTarget.block(OVERWORLD, new BlockPos(x, y, z));
    }

    @Test
    void breakOrReplaceTheSupportingBlockDetachesOnce() {
        LockRecord lock = LockService.create(data, at(0, 64, 0), UUID.randomUUID()).orElseThrow();
        assertTrue(LockService.at(data, at(0, 64, 0)).isPresent());

        assertTrue(LockService.detach(data, lock.lockId()), "the first detach does the work");
        assertFalse(LockService.detach(data, lock.lockId()), "and the second one does nothing at all");
        assertFalse(LockService.detach(data, lock.lockId()));

        assertTrue(LockService.at(data, at(0, 64, 0)).isEmpty(), "nothing is locked there any more");
        assertTrue(LockService.byId(data, lock.lockId()).isPresent(),
                "but the row survives: keys are still bound to it");
    }

    @Test
    void orphanRecoveryRouteExists() {
        LockRecord lock = LockService.create(data, at(2, 64, 2), null).orElseThrow();
        assertTrue(LockService.orphans(data).isEmpty(), "an attached lock is not an orphan");

        LockService.detach(data, lock.lockId());
        assertEquals(1, LockService.orphans(data).size());
        assertEquals(lock.lockId(), LockService.orphans(data).get(0).lockId());

        assertTrue(LockService.forget(data, lock.lockId()), "an operator can drop the row deliberately");
        assertTrue(LockService.orphans(data).isEmpty());
        assertTrue(LockService.byId(data, lock.lockId()).isEmpty());
    }

    @Test
    void oneCanonicalOwnerPerTargetGroup() {
        LockService.create(data, at(5, 64, 5), null).orElseThrow();
        assertTrue(LockService.create(data, at(5, 64, 5), null).isEmpty(),
                "a second lock on one group is refused, never stacked");
        assertEquals(1, data.locks().size());
    }

    @Test
    void movingALockKeepsEveryKeyWorkingAndRefusesAnOccupiedGroup() {
        LockRecord lock = LockService.create(data, at(7, 64, 7), null).orElseThrow();
        KeyBinding key = KeyBinding.forLock(lock, "Shed");
        LockService.create(data, at(9, 64, 9), null).orElseThrow();

        assertTrue(LockService.moveTo(data, lock.lockId(), at(9, 64, 9)).isEmpty(),
                "somebody else's group is refused");

        LockRecord moved = LockService.moveTo(data, lock.lockId(), at(8, 64, 8)).orElseThrow();
        assertTrue(key.opens(moved), "moving a padlock does not recut anybody's key");
        assertTrue(LockService.at(data, at(8, 64, 8)).isPresent());
        assertTrue(LockService.at(data, at(7, 64, 7)).isEmpty());
    }

    @Test
    void rekeyingInvalidatesEveryCopyWithoutMovingTheLock() {
        LockRecord lock = LockService.create(data, at(3, 64, 3), null).orElseThrow();
        KeyBinding oldCopy = KeyBinding.forLock(lock, "Gate");

        LockRecord rekeyed = LockService.rekey(data, lock.lockId()).orElseThrow();
        assertEquals(lock.lockId(), rekeyed.lockId(), "the identity is what the block points at");
        assertFalse(oldCopy.opens(rekeyed));
        assertTrue(LockService.at(data, at(3, 64, 3)).isPresent(), "and it is still on the same block");
    }

    @Test
    void theIndexNeverReportsALockWhereThereIsNone() {
        // The dangerous cache answer is the negative one: a stale "nothing here" is how a hopper
        // drains a locked safe. Ask before, create, ask again.
        assertTrue(LockService.at(data, at(11, 64, 11)).isEmpty());
        LockRecord lock = LockService.create(data, at(11, 64, 11), null).orElseThrow();
        assertEquals(lock.lockId(), LockService.at(data, at(11, 64, 11)).orElseThrow().lockId());

        LockService.setLocked(data, lock.lockId(), false);
        assertFalse(LockService.at(data, at(11, 64, 11)).orElseThrow().locked());
    }

    @Test
    void lockTableSurvivesSaveAndLoadWithItsBindings() {
        LockRecord lock = LockService.create(data, at(1, 70, 1), UUID.randomUUID()).orElseThrow();
        KeyBinding key = KeyBinding.forLock(lock, "Safe");
        LockService.setReinforced(data, lock.lockId(), true);

        // 1.21 threads a registry lookup through save and load, because an item stack in the store
        // serialises through ItemStack.CODEC. Nothing in the lock table needs one, and EMPTY is what
        // every other world-data test on this line passes.
        CrimeWorldData loaded = CrimeWorldData.load(
                data.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);
        LockService.invalidate();
        Optional<LockRecord> reloaded = LockService.at(loaded, at(1, 70, 1));
        assertTrue(reloaded.isPresent());
        assertTrue(reloaded.get().reinforced());
        assertTrue(key.opens(reloaded.get()), "a key cut before a restart still opens the lock after it");
    }
}
