package dev.otectus.mcacrime.locks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One lock, through NBT and back, and the binding revision that makes rekeying work.
 *
 * <p>The behaviour worth asserting is that identity and position are separate things: a lock keeps
 * its id when it moves, and a key stops working when the binding advances even though nothing about
 * the position changed. That separation is what stops a stale lockpick session from opening a
 * replacement lock placed where the old one stood.
 */
class LockRecordNbtTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    @Test
    void aBlockLockRoundTrips() {
        UUID lockId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        LockRecord lock = new LockRecord(lockId, 3L, LockTarget.block(OVERWORLD, new BlockPos(8, 70, 9)),
                owner, 12, true, true, 7L);

        LockRecord loaded = LockRecord.load(lock.save()).orElseThrow();

        assertEquals(lock, loaded);
        assertEquals(LockTarget.Kind.BLOCK, loaded.target().kind());
        assertEquals(new BlockPos(8, 70, 9), loaded.target().blockPos().orElseThrow());
        assertEquals(owner, loaded.ownerId().orElseThrow());
        assertEquals(12, loaded.authorityVillage());
        assertTrue(loaded.reinforced());
    }

    @Test
    void anEntityLockRoundTrips() {
        UUID padlock = UUID.randomUUID();
        LockRecord lock = LockRecord.of(UUID.randomUUID(), LockTarget.entity(padlock), null);

        LockRecord loaded = LockRecord.load(lock.save()).orElseThrow();

        assertEquals(lock, loaded);
        assertEquals(padlock, loaded.target().entity().orElseThrow());
        assertTrue(loaded.target().blockPos().isEmpty(), "an entity target has no position");
        assertTrue(loaded.locked(), "a new lock is locked");
        assertEquals(1L, loaded.bindingRevision());
    }

    @Test
    void rekeyingInvalidatesEveryOldKeyAndKeepsTheIdentity() {
        LockRecord lock = LockRecord.of(UUID.randomUUID(), LockTarget.block(OVERWORLD, BlockPos.ZERO), null);
        long keyBinding = lock.bindingRevision();

        LockRecord rekeyed = lock.rekeyed();

        assertEquals(lock.lockId(), rekeyed.lockId(), "the block and the padlock point at the id");
        assertFalse(rekeyed.accepts(keyBinding), "an old key copy still opens it");
        assertTrue(rekeyed.accepts(rekeyed.bindingRevision()));
        assertTrue(lock.accepts(keyBinding), "the original is immutable");
    }

    @Test
    void movingALockKeepsEveryKeyWorking() {
        LockRecord lock = LockRecord.of(UUID.randomUUID(), LockTarget.block(OVERWORLD, BlockPos.ZERO), null);

        LockRecord moved = lock.movedTo(LockTarget.block(OVERWORLD, new BlockPos(1, 2, 3)));

        assertTrue(moved.accepts(lock.bindingRevision()), "moving a padlock is not rekeying it");
        assertEquals(new BlockPos(1, 2, 3), moved.target().blockPos().orElseThrow());
    }

    @Test
    void lockingAndReinforcingAdvanceTheRevisionOnlyWhenTheyChangeSomething() {
        LockRecord lock = LockRecord.of(UUID.randomUUID(), LockTarget.block(OVERWORLD, BlockPos.ZERO), null);

        assertEquals(lock, lock.locked(true), "no change, no revision");
        LockRecord unlocked = lock.locked(false);
        assertFalse(unlocked.locked());
        assertEquals(lock.revision() + 1L, unlocked.revision());

        LockRecord reinforced = lock.reinforced(true);
        assertTrue(reinforced.reinforced());
        assertEquals(lock.revision() + 1L, reinforced.revision());
    }

    @Test
    void aTargetlessLockKeepsItsIdentitySoKeysStayMeaningful() {
        LockRecord orphan = new LockRecord(UUID.randomUUID(), 2L, LockTarget.none(), null, 0, true,
                false, 1L);

        LockRecord loaded = LockRecord.load(orphan.save()).orElseThrow();

        assertEquals(orphan, loaded);
        assertFalse(loaded.target().present());
        assertTrue(loaded.accepts(2L), "the keys bound to a removed lock still name it");
    }

    @Test
    void aRowWithNoLockIdIsEmpty() {
        assertEquals(Optional.empty(), LockRecord.load(null));
        assertEquals(Optional.empty(), LockRecord.load(new CompoundTag()));
    }

    @Test
    void anUnreadableTargetReadsAsNoneRatherThanThrowing() {
        CompoundTag broken = new CompoundTag();
        broken.putString("kind", "sideways");
        assertEquals(LockTarget.none(), LockTarget.load(broken));
        assertEquals(LockTarget.none(), LockTarget.load(null));
        assertEquals(LockTarget.none(), LockTarget.load(new CompoundTag()));
    }
}
