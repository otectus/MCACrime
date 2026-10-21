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
 *
 * <p><b>Entries keep what they carry (0.8.0).</b> Each entry is a {@link KeyBinding#save()} compound,
 * and an entry may carry more: {@link #TAG_CUSTOM_NAME}, the anvil name of the key that went onto the
 * ring, which comes back onto the key when it is taken off. Every operation here works on the raw
 * entries, so adding, replacing or removing one key never strips another key's extra data -- before
 * 0.8.0 every change rebuilt the list from bare bindings and a renamed key came back as plain "Key".
 */
public final class KeyRingBindings {

    /** The model predicate's key. Written from the real count, never from a guess. */
    public static final String TAG_KEYS = "Keys";

    /**
     * An entry's copy of the key's own display name (component JSON), when it had one. Presentation
     * only: never compared, never trusted for access, and restored onto the key on disassembly.
     */
    public static final String TAG_CUSTOM_NAME = "CustomName";

    /** The hard ceiling a configured capacity is clamped to, so a typo cannot make a ring unbounded. */
    public static final int MAX_CAPACITY = 64;

    private KeyRingBindings() {
    }

    /** One key on a ring: its binding, and the key's own display name when it had one. */
    public record Entry(KeyBinding binding, @Nullable String customName) {

        public Entry {
            customName = customName == null || customName.isBlank() ? null : customName;
        }

        /** The compound this entry is stored as. */
        public CompoundTag save() {
            CompoundTag tag = binding.save();
            if (customName != null) {
                tag.putString(TAG_CUSTOM_NAME, customName);
            }
            return tag;
        }

        /** An entry for a key stack's tag: its binding, plus its anvil name if it has one. */
        public static Optional<Entry> ofKey(@Nullable CompoundTag keyTag) {
            return KeyBinding.read(keyTag).map(binding -> new Entry(binding, customNameOf(keyTag)));
        }

        static Optional<Entry> load(@Nullable CompoundTag entry) {
            return KeyBinding.load(entry).map(binding -> new Entry(binding,
                    entry.contains(TAG_CUSTOM_NAME, Tag.TAG_STRING) ? entry.getString(TAG_CUSTOM_NAME) : null));
        }

        /** Writes this entry onto a key stack's tag: the binding, and the display name if there was one. */
        public void writeToKey(CompoundTag keyTag) {
            binding.writeTo(keyTag);
            if (customName != null) {
                CompoundTag display = keyTag.contains("display", Tag.TAG_COMPOUND)
                        ? keyTag.getCompound("display") : new CompoundTag();
                display.putString("Name", customName);
                keyTag.put("display", display);
            }
        }
    }

    /** The anvil name on a stack's tag, as component JSON, or null. */
    @Nullable
    public static String customNameOf(@Nullable CompoundTag stackTag) {
        if (stackTag == null || !stackTag.contains("display", Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag display = stackTag.getCompound("display");
        return display.contains("Name", Tag.TAG_STRING) ? display.getString("Name") : null;
    }

    // --- raw entries ---------------------------------------------------------------------------------

    private static ListTag list(@Nullable CompoundTag ringTag) {
        return ringTag != null && ringTag.contains(KeyBinding.TAG_BOUND_LOCKS, Tag.TAG_LIST)
                ? ringTag.getList(KeyBinding.TAG_BOUND_LOCKS, Tag.TAG_COMPOUND) : new ListTag();
    }

    private static void store(CompoundTag ringTag, ListTag list) {
        ringTag.put(KeyBinding.TAG_BOUND_LOCKS, list);
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            if (KeyBinding.load(list.getCompound(i)).isPresent()) {
                count++;
            }
        }
        ringTag.putInt(TAG_KEYS, count);
    }

    private static int rawIndexOf(ListTag list, @Nullable UUID lockId) {
        if (lockId == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            Optional<KeyBinding> binding = KeyBinding.load(list.getCompound(i));
            if (binding.isPresent() && lockId.equals(binding.get().lockId())) {
                return i;
            }
        }
        return -1;
    }

    /** Every key on this ring with its extra data, in the order they were added. */
    public static List<Entry> entries(@Nullable CompoundTag ringTag) {
        List<Entry> entries = new ArrayList<>();
        ListTag list = list(ringTag);
        for (int i = 0; i < list.size(); i++) {
            Entry.load(list.getCompound(i)).ifPresent(entries::add);
        }
        return entries;
    }

    /** Every binding on this ring, in the order they were added. */
    public static List<KeyBinding> read(@Nullable CompoundTag ringTag) {
        List<KeyBinding> bindings = new ArrayList<>();
        for (Entry entry : entries(ringTag)) {
            bindings.add(entry.binding());
        }
        return bindings;
    }

    /** Replaces the whole list with bare bindings. Kept for callers that have no extra data to keep. */
    public static void write(@Nullable CompoundTag ringTag, @Nullable List<KeyBinding> bindings) {
        List<Entry> entries = new ArrayList<>();
        if (bindings != null) {
            bindings.forEach(binding -> entries.add(new Entry(binding, null)));
        }
        writeEntries(ringTag, entries);
    }

    /** Replaces the whole list, extra data included. */
    public static void writeEntries(@Nullable CompoundTag ringTag, @Nullable List<Entry> entries) {
        if (ringTag == null) {
            return;
        }
        ListTag list = new ListTag();
        if (entries != null) {
            entries.forEach(entry -> list.add(entry.save()));
        }
        store(ringTag, list);
    }

    /** How many keys are on the ring. */
    public static int count(@Nullable CompoundTag ringTag) {
        return read(ringTag).size();
    }

    /** The position of the key for {@code lockId}, or -1. Compared with {@code equals}, never {@code ==}. */
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

    /** Whether the ring carries a key for this lock at any revision. */
    public static boolean holds(@Nullable CompoundTag ringTag, @Nullable UUID lockId) {
        return indexOf(ringTag, lockId) >= 0;
    }

    /** The key the ring carries for this lock, current or not. */
    public static Optional<KeyBinding> find(@Nullable CompoundTag ringTag, @Nullable UUID lockId) {
        int index = indexOf(ringTag, lockId);
        return index < 0 ? Optional.empty() : Optional.of(read(ringTag).get(index));
    }

    /**
     * The key on this ring that opens {@code lock} right now, if there is one.
     *
     * <p>"Opens" includes the revision: a ring that still carries a pre-rekey key for this lock does
     * not open it, which is the guarantee a rekey has to give.
     */
    public static Optional<KeyBinding> opening(@Nullable CompoundTag ringTag, @Nullable LockRecord lock) {
        if (lock == null) {
            return Optional.empty();
        }
        return find(ringTag, lock.lockId()).filter(binding -> binding.opens(lock));
    }

    /**
     * Adds one binding if there is room and the ring does not already carry a key for that lock.
     *
     * <p>Refusing over capacity rather than evicting is the point: a ring that silently dropped its
     * oldest key would be a ring that quietly locks its owner out of something.
     */
    public static boolean add(@Nullable CompoundTag ringTag, @Nullable KeyBinding binding, int capacity) {
        return binding != null && addEntry(ringTag, new Entry(binding, null), capacity);
    }

    /** As {@link #add}, keeping the entry's extra data. */
    public static boolean addEntry(@Nullable CompoundTag ringTag, @Nullable Entry entry, int capacity) {
        if (ringTag == null || entry == null) {
            return false;
        }
        if (count(ringTag) >= clampCapacity(capacity)) {
            return false;
        }
        ListTag list = list(ringTag).copy();
        if (rawIndexOf(list, entry.binding().lockId()) >= 0) {
            return false; // one key per lock; a second copy of the same key adds nothing
        }
        list.add(entry.save());
        store(ringTag, list);
        return true;
    }

    /** Replaces the stale key for the same lock during an authorized post-rekey initialization. */
    public static boolean replace(@Nullable CompoundTag ringTag, @Nullable KeyBinding binding) {
        if (ringTag == null || binding == null) {
            return false;
        }
        ListTag list = list(ringTag).copy();
        int index = rawIndexOf(list, binding.lockId());
        if (index < 0) {
            return false;
        }
        CompoundTag updated = list.getCompound(index).copy();
        CompoundTag fresh = binding.save();
        for (String key : List.of(KeyBinding.TAG_LOCK_ID, KeyBinding.TAG_BINDING_REVISION, KeyBinding.TAG_NAME)) {
            updated.remove(key);
            if (fresh.contains(key)) {
                updated.put(key, fresh.get(key).copy());
            }
        }
        list.set(index, updated);
        store(ringTag, list);
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
        return removeLastEntry(ringTag).map(Entry::binding);
    }

    /** As {@link #removeLast}, returning the entry with its extra data. */
    public static Optional<Entry> removeLastEntry(@Nullable CompoundTag ringTag) {
        if (ringTag == null) {
            return Optional.empty();
        }
        ListTag list = list(ringTag).copy();
        for (int i = list.size() - 1; i >= 0; i--) {
            Optional<Entry> entry = Entry.load(list.getCompound(i));
            if (entry.isPresent()) {
                list.remove(i);
                store(ringTag, list);
                return entry;
            }
        }
        return Optional.empty();
    }

    /** Removes the key for one lock, if it is there. */
    public static boolean remove(@Nullable CompoundTag ringTag, @Nullable UUID lockId) {
        if (ringTag == null) {
            return false;
        }
        ListTag list = list(ringTag).copy();
        int index = rawIndexOf(list, lockId);
        if (index < 0) {
            return false;
        }
        list.remove(index);
        store(ringTag, list);
        return true;
    }

    /** A configured capacity, clamped into something a ring can actually be. */
    public static int clampCapacity(int capacity) {
        return Math.max(0, Math.min(MAX_CAPACITY, capacity));
    }
}
