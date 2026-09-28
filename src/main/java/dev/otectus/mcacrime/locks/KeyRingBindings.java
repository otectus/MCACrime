package dev.otectus.mcacrime.locks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The bindings on a key ring, as list operations on its tag (§3.7).
 *
 * <p>Pure: everything here takes and returns a {@link CompoundTag}, so the ring's rules — capacity,
 * duplicates, ordering, lossless disassembly — can be asserted without a world, an item registry or a
 * crafting grid. The item and the recipes are thin wrappers over these.
 *
 * <p>Two upstream behaviours are deliberately not reproduced. Its index lookup compares UUIDs with
 * {@code ==}, so it almost never finds anything; and it keeps a cosmetic {@code Keys} integer that is
 * stamped to 1 or 2 by the item's own tick and does not match the real number of bindings, while the
 * capacity check is made against <em>that</em> number. Here there is one count, {@link #count}, and it
 * is the length of the list.
 */
public final class KeyRingBindings {

    /** The model predicate's key. Written from the real count, never from a guess. */
    public static final String TAG_KEYS = "Keys";

    /** The hard ceiling a configured capacity is clamped to, so a typo cannot make a ring unbounded. */
    public static final int MAX_CAPACITY = 64;

    private KeyRingBindings() {
    }

    /** Every binding on this ring, in the order they were added. */
    public static List<KeyBinding> read(@Nullable CompoundTag ringTag) {
        List<KeyBinding> bindings = new ArrayList<>();
        if (ringTag == null || !ringTag.contains(KeyBinding.TAG_BOUND_LOCKS, Tag.TAG_LIST)) {
            return bindings;
        }
        ListTag list = ringTag.getList(KeyBinding.TAG_BOUND_LOCKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            KeyBinding.load(list.getCompound(i)).ifPresent(bindings::add);
        }
        return bindings;
    }

    /**
     * Replaces the whole list, and keeps {@link #TAG_KEYS} honest.
     *
     * <p>The count is written here and nowhere else, which is what stops the model predicate and the
     * capacity check from disagreeing about how full a ring is.
     */
    public static void write(@Nullable CompoundTag ringTag, @Nullable List<KeyBinding> bindings) {
        if (ringTag == null) {
            return;
        }
        List<KeyBinding> safe = bindings == null ? List.of() : bindings;
        ListTag list = new ListTag();
        safe.forEach(binding -> list.add(binding.save()));
        ringTag.put(KeyBinding.TAG_BOUND_LOCKS, list);
        ringTag.putInt(TAG_KEYS, safe.size());
    }

    /** How many keys are on this ring. */
    public static int count(@Nullable CompoundTag ringTag) {
        return read(ringTag).size();
    }

    /** Where {@code lockId} sits on the ring, or -1. {@code equals}, never {@code ==}. */
    public static int indexOf(@Nullable CompoundTag ringTag, @Nullable UUID lockId) {
        if (lockId == null) {
            return -1;
        }
        List<KeyBinding> bindings = read(ringTag);
        for (int i = 0; i < bindings.size(); i++) {
            if (lockId.equals(bindings.get(i).lockId())) {
                return i;
            }
        }
        return -1;
    }

    /** Whether this ring holds a key for {@code lockId}, at any revision. */
    public static boolean holds(@Nullable CompoundTag ringTag, @Nullable UUID lockId) {
        return indexOf(ringTag, lockId) >= 0;
    }

    /** The binding for {@code lockId}, if the ring carries one. */
    public static Optional<KeyBinding> find(@Nullable CompoundTag ringTag, @Nullable UUID lockId) {
        int index = indexOf(ringTag, lockId);
        return index < 0 ? Optional.empty() : Optional.of(read(ringTag).get(index));
    }

    /**
     * The binding on this ring that opens {@code lock}, if any.
     *
     * <p>A ring is a bundle of keys, not a master key: it opens exactly the locks it carries a current
     * key for, and a stale copy on a ring is as useless as a stale copy in a pocket.
     */
    public static Optional<KeyBinding> opening(@Nullable CompoundTag ringTag, @Nullable LockRecord lock) {
        if (lock == null) {
            return Optional.empty();
        }
        return find(ringTag, lock.lockId()).filter(binding -> binding.opens(lock));
    }

    /**
     * Adds one binding if there is room and it is not already there.
     *
     * <p>Refusing at capacity rather than evicting is what makes a <em>reduced</em> capacity safe: an
     * over-full ring keeps every key it already has and simply accepts no more (§3.7). Nothing here
     * ever removes a binding to make room.
     *
     * @return true when the ring changed
     */
    public static boolean add(@Nullable CompoundTag ringTag, @Nullable KeyBinding binding, int capacity) {
        if (ringTag == null || binding == null) {
            return false;
        }
        List<KeyBinding> bindings = read(ringTag);
        if (bindings.size() >= clampCapacity(capacity)) {
            return false;
        }
        for (KeyBinding existing : bindings) {
            if (existing.lockId().equals(binding.lockId())) {
                return false; // one key per lock; a second copy of the same key adds nothing
            }
        }
        bindings.add(binding);
        write(ringTag, bindings);
        return true;
    }

    /** Replaces the stale key for the same lock during an authorized post-rekey initialization. */
    public static boolean replace(@Nullable CompoundTag ringTag, @Nullable KeyBinding binding) {
        if (ringTag == null || binding == null) {
            return false;
        }
        int index = indexOf(ringTag, binding.lockId());
        if (index < 0) {
            return false;
        }
        List<KeyBinding> bindings = read(ringTag);
        bindings.set(index, binding);
        write(ringTag, bindings);
        return true;
    }

    /** Whether one more key would fit. */
    public static boolean hasRoom(@Nullable CompoundTag ringTag, int capacity) {
        return count(ringTag) < clampCapacity(capacity);
    }

    /**
     * Takes the last binding off the ring and returns it.
     *
     * <p>Last rather than first, so repeated disassembly walks back through the ring in a predictable
     * order and a player can see which key comes off next.
     */
    public static Optional<KeyBinding> removeLast(@Nullable CompoundTag ringTag) {
        List<KeyBinding> bindings = read(ringTag);
        if (bindings.isEmpty()) {
            return Optional.empty();
        }
        KeyBinding removed = bindings.remove(bindings.size() - 1);
        write(ringTag, bindings);
        return Optional.of(removed);
    }

    /** Removes the key for one lock, if it is there. */
    public static boolean remove(@Nullable CompoundTag ringTag, @Nullable UUID lockId) {
        int index = indexOf(ringTag, lockId);
        if (index < 0) {
            return false;
        }
        List<KeyBinding> bindings = read(ringTag);
        bindings.remove(index);
        write(ringTag, bindings);
        return true;
    }

    /** A configured capacity, clamped into something a ring can actually be. */
    public static int clampCapacity(int capacity) {
        return Math.max(0, Math.min(MAX_CAPACITY, capacity));
    }
}
