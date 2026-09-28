package dev.otectus.mcacrime.locks;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The access table: which key opens which lock, and why a refusal is which refusal (M3.1, ledger L02).
 *
 * <p>Three of these cases are the specification's acceptance test in §21.2 — a wrong key, an old copy
 * of a rekeyed key, and another player's key — and none of them may open anything. The fourth is the
 * one that is easy to get wrong in the other direction: a generic key tag is <em>not</em> a master key,
 * and this table has no way to express one, which is the point.
 */
class LockAccessTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private static LockRecord lock() {
        return LockRecord.of(UUID.randomUUID(), LockTarget.block(OVERWORLD, new BlockPos(1, 64, 1)),
                UUID.randomUUID());
    }

    @Test
    void wrongKeyOldCopyForeignSession() {
        LockRecord lock = lock();
        KeyBinding correct = KeyBinding.forLock(lock, "Front Door");

        assertEquals(LockAccess.Decision.ALLOW, LockAccess.evaluate(lock, correct, false));

        KeyBinding somebodyElses = KeyBinding.of(UUID.randomUUID(), lock.bindingRevision());
        assertEquals(LockAccess.Decision.DENY_WRONG_KEY,
                LockAccess.evaluate(lock, somebodyElses, false),
                "another lock's key is a wrong key, not a stale one");

        LockRecord rekeyed = lock.rekeyed();
        assertEquals(LockAccess.Decision.DENY_REKEYED, LockAccess.evaluate(rekeyed, correct, false),
                "the copy cut before the rekey stops working");
        assertFalse(LockAccess.opens(rekeyed, correct));
        assertTrue(LockAccess.opens(rekeyed, KeyBinding.forLock(rekeyed, "Front Door")),
                "a key cut after the rekey works");

        assertEquals(LockAccess.Decision.DENY_NO_KEY, LockAccess.evaluate(lock, null, false));
    }

    @Test
    void anUnlockedOrAbsentLockStopsNobody() {
        LockRecord lock = lock();
        assertEquals(LockAccess.Decision.UNLOCKED, LockAccess.evaluate(lock.locked(false), null, false));
        assertEquals(LockAccess.Decision.UNLOCKED, LockAccess.evaluate(null, null, false),
                "a lock nobody recorded is not a lock");
    }

    @Test
    void masterAuthorityIsGrantedByTheServerNotByAnItemTag() {
        LockRecord lock = lock();
        assertEquals(LockAccess.Decision.ALLOW_MASTER, LockAccess.evaluate(lock, null, true));
        // The signature is the assertion: there is no stack and no tag in it. A master key is an
        // authorisation the server decides, which is why mcacrime:keys can never become one (§3.7).
        assertEquals(LockAccess.Decision.DENY_NO_KEY, LockAccess.evaluate(lock, null, false));
    }

    @Test
    void togglingNeedsACurrentKeyEvenWhenPassingWouldNot() {
        LockRecord lock = lock();
        KeyBinding correct = KeyBinding.forLock(lock, null);
        LockRecord open = lock.locked(false);
        assertTrue(LockAccess.evaluate(open, null, false).allowed(),
                "an unlocked lock lets anybody through");
        assertFalse(LockAccess.mayToggle(open, null, false),
                "but re-locking somebody else's door needs their key");
        assertTrue(LockAccess.mayToggle(open, correct, false));
        assertTrue(LockAccess.mayToggle(open, null, true), "an operator may always act");
    }

    @Test
    void ringsCarryKeysAndOpenExactlyWhatTheyHold() {
        LockRecord mine = lock();
        LockRecord yours = lock();
        net.minecraft.nbt.CompoundTag ring = new net.minecraft.nbt.CompoundTag();
        assertTrue(KeyRingBindings.add(ring, KeyBinding.forLock(mine, "Mine"), 16));

        assertTrue(KeyRingBindings.opening(ring, mine).isPresent());
        assertFalse(KeyRingBindings.opening(ring, yours).isPresent(),
                "a ring is a bundle of keys, never a master key");
        assertFalse(KeyRingBindings.opening(ring, mine.rekeyed()).isPresent(),
                "and a stale copy on a ring is as useless as a stale copy in a pocket");
    }

    @Test
    void ringLookupsUseEqualsRatherThanIdentity() {
        LockRecord lock = lock();
        net.minecraft.nbt.CompoundTag ring = new net.minecraft.nbt.CompoundTag();
        KeyRingBindings.add(ring, KeyBinding.forLock(lock, "Cellar"), 16);
        // A fresh UUID instance with the same value. Upstream compares these with ==, so its own ring
        // almost never finds the key it is carrying (items/KeyRingItem.java:183).
        UUID sameValue = new UUID(lock.lockId().getMostSignificantBits(),
                lock.lockId().getLeastSignificantBits());
        assertTrue(KeyRingBindings.holds(ring, sameValue));
        assertEquals(0, KeyRingBindings.indexOf(ring, sameValue));
    }
}
