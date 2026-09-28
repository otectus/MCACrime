package dev.otectus.mcacrime.locks;

import dev.otectus.mcacrime.state.CrimeDataComponents;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The bindings on a key ring, as list operations (§3.7).
 *
 * <p>Pure at the bottom: every rule — capacity, duplicates, ordering, lossless disassembly — is a
 * function of a {@code List<KeyBinding>}, so it can be asserted without a world, an item registry or
 * a crafting grid. The stack-facing overloads are thin wrappers that read and write the one component
 * the ring carries.
 *
 * <p>Two upstream behaviours are deliberately not reproduced. Its index lookup compares UUIDs with
 * {@code ==}, so it almost never finds anything; and it keeps a cosmetic {@code Keys} integer that is
 * stamped to 1 or 2 by the item's own tick and does not match the real number of bindings, while the
 * capacity check is made against <em>that</em> number. Here there is one count, {@link #count}, and it
 * is the length of the list — which is also what the client's model predicate reads.
 */
public final class KeyRingBindings {

    /** The hard ceiling a configured capacity is clamped to, so a typo cannot make a ring unbounded. */
    public static final int MAX_CAPACITY = 64;

    private KeyRingBindings() {
    }

    // --- stacks -------------------------------------------------------------------------------------

    /** Every binding on this ring, in the order they were added. */
    public static List<KeyBinding> read(@Nullable ItemStack ring) {
        if (ring == null || ring.isEmpty()) {
            return List.of();
        }
        List<KeyBinding> stored = ring.get(CrimeDataComponents.KEY_RING_CONTENTS.get());
        return stored == null ? List.of() : List.copyOf(stored);
    }

    /** Replaces the whole list. An empty list clears the component rather than storing nothing. */
    public static void write(@Nullable ItemStack ring, @Nullable List<KeyBinding> bindings) {
        if (ring == null || ring.isEmpty()) {
            return;
        }
        if (bindings == null || bindings.isEmpty()) {
            ring.remove(CrimeDataComponents.KEY_RING_CONTENTS.get());
            return;
        }
        ring.set(CrimeDataComponents.KEY_RING_CONTENTS.get(), List.copyOf(bindings));
    }

    /** How many keys are on this ring. */
    public static int count(@Nullable ItemStack ring) {
        return read(ring).size();
    }

    /** Whether this ring holds a key for {@code lockId}, at any revision. */
    public static boolean holds(@Nullable ItemStack ring, @Nullable UUID lockId) {
        return indexOf(read(ring), lockId) >= 0;
    }

    /** The binding for {@code lockId}, if the ring carries one. */
    public static Optional<KeyBinding> find(@Nullable ItemStack ring, @Nullable UUID lockId) {
        return find(read(ring), lockId);
    }

    /**
     * The binding on this ring that opens {@code lock}, if any.
     *
     * <p>A ring is a bundle of keys, not a master key: it opens exactly the locks it carries a current
     * key for, and a stale copy on a ring is as useless as a stale copy in a pocket.
     */
    public static Optional<KeyBinding> opening(@Nullable ItemStack ring, @Nullable LockRecord lock) {
        return opening(read(ring), lock);
    }

    /** Adds one binding to a ring stack if there is room and it is not already there. */
    public static boolean add(@Nullable ItemStack ring, @Nullable KeyBinding binding, int capacity) {
        if (ring == null || ring.isEmpty()) {
            return false;
        }
        List<KeyBinding> bindings = new ArrayList<>(read(ring));
        if (!add(bindings, binding, capacity)) {
            return false;
        }
        write(ring, bindings);
        return true;
    }

    /** Whether one more key would fit on this ring. */
    public static boolean hasRoom(@Nullable ItemStack ring, int capacity) {
        return count(ring) < clampCapacity(capacity);
    }

    // --- pure ---------------------------------------------------------------------------------------

    /** Where {@code lockId} sits in a list, or -1. {@code equals}, never {@code ==}. */
    public static int indexOf(@Nullable List<KeyBinding> bindings, @Nullable UUID lockId) {
        if (bindings == null || lockId == null) {
            return -1;
        }
        for (int i = 0; i < bindings.size(); i++) {
            if (lockId.equals(bindings.get(i).lockId())) {
                return i;
            }
        }
        return -1;
    }

    public static Optional<KeyBinding> find(@Nullable List<KeyBinding> bindings, @Nullable UUID lockId) {
        int index = indexOf(bindings, lockId);
        return index < 0 ? Optional.empty() : Optional.of(bindings.get(index));
    }

    public static Optional<KeyBinding> opening(@Nullable List<KeyBinding> bindings,
                                               @Nullable LockRecord lock) {
        if (lock == null) {
            return Optional.empty();
        }
        return find(bindings, lock.lockId()).filter(binding -> binding.opens(lock));
    }

    /**
     * Adds one binding to a mutable list if there is room and it is not already there.
     *
     * <p>Refusing at capacity rather than evicting is what makes a <em>reduced</em> capacity safe: an
     * over-full ring keeps every key it already has and simply accepts no more (§3.7). Nothing here
     * ever removes a binding to make room.
     *
     * @return true when the list changed
     */
    public static boolean add(@Nullable List<KeyBinding> bindings, @Nullable KeyBinding binding,
                              int capacity) {
        if (bindings == null || binding == null) {
            return false;
        }
        if (bindings.size() >= clampCapacity(capacity)) {
            return false;
        }
        for (KeyBinding existing : bindings) {
            if (existing.lockId().equals(binding.lockId())) {
                return false; // one key per lock; a second copy of the same key adds nothing
            }
        }
        bindings.add(binding);
        return true;
    }

    /**
     * Takes the last binding off a mutable list and returns it.
     *
     * <p>Last rather than first, so repeated disassembly walks back through the ring in a predictable
     * order and a player can see which key comes off next.
     */
    public static Optional<KeyBinding> removeLast(@Nullable List<KeyBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(bindings.remove(bindings.size() - 1));
    }

    /** Removes the key for one lock from a mutable list, if it is there. */
    public static boolean remove(@Nullable List<KeyBinding> bindings, @Nullable UUID lockId) {
        int index = indexOf(bindings, lockId);
        if (index < 0) {
            return false;
        }
        bindings.remove(index);
        return true;
    }

    /** A configured capacity, clamped into something a ring can actually be. */
    public static int clampCapacity(int capacity) {
        return Math.max(0, Math.min(MAX_CAPACITY, capacity));
    }
}
