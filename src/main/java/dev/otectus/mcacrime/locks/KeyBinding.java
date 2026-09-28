package dev.otectus.mcacrime.locks;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * What a key stack knows about the lock it was cut for (§3.7).
 *
 * <p>Three fields, and the middle one is the whole point. Upstream's key carries a bare {@code UUID},
 * so a "reset" that wants to invalidate old copies has no way to say so except by rerolling the
 * identity — which orphans the block, the padlock entity and every audit reference at once. Carrying
 * the binding revision with the id means a rekey invalidates every copy while the lock stays the same
 * lock.
 *
 * <p>The name is presentation: a key copied from a named key keeps the name so a ring of eight keys
 * is usable. It is never compared and never trusted for access.
 *
 * <p>Comparison is {@link UUID#equals}, everywhere, deliberately: upstream's ring index compares its
 * UUIDs with {@code ==} ({@code items/KeyRingItem.java:183}) and therefore essentially never finds a
 * key it is holding.
 *
 * @param lockId          the lock identity this key opens
 * @param bindingRevision the lock's binding revision when the key was cut
 * @param name            the display name carried through copying, or null
 */
public record KeyBinding(UUID lockId, long bindingRevision, @Nullable String name) {

    /** The stack-tag key the binding lives under. */
    public static final String TAG_LOCK_ID = "LockId";
    public static final String TAG_BINDING_REVISION = "BindingRevision";
    public static final String TAG_NAME = "LockName";
    /** The ring's list of bindings, and the mold's single copied one. */
    public static final String TAG_BOUND_LOCKS = "BoundLocks";
    public static final String TAG_COPIED_KEY = "CopiedKey";

    public KeyBinding {
        bindingRevision = Math.max(1L, bindingRevision);
        name = name == null || name.isBlank() ? null : name;
    }

    public static KeyBinding of(UUID lockId, long bindingRevision) {
        return new KeyBinding(lockId, bindingRevision, null);
    }

    /** A key cut for {@code lock} right now. */
    public static KeyBinding forLock(LockRecord lock, @Nullable String name) {
        return new KeyBinding(lock.lockId(), lock.bindingRevision(), name);
    }

    public Optional<String> displayName() {
        return Optional.ofNullable(name);
    }

    /**
     * Whether this key still opens {@code lock}.
     *
     * <p>Identity <em>and</em> revision. A key for another lock is a wrong key; a key for this lock at
     * an older revision is a key somebody has been locked out of, and the two are different answers a
     * player deserves to be told apart.
     */
    public boolean opens(@Nullable LockRecord lock) {
        return lock != null && lock.lockId().equals(lockId) && lock.accepts(bindingRevision);
    }

    /** This binding written into its own compound: a ring entry, or a mold's copy. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_LOCK_ID, lockId);
        tag.putLong(TAG_BINDING_REVISION, bindingRevision);
        if (name != null) {
            tag.putString(TAG_NAME, name);
        }
        return tag;
    }

    /** One binding out of its own compound. */
    public static Optional<KeyBinding> load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.hasUUID(TAG_LOCK_ID)) {
            return Optional.empty();
        }
        return Optional.of(new KeyBinding(
                tag.getUUID(TAG_LOCK_ID),
                tag.contains(TAG_BINDING_REVISION) ? tag.getLong(TAG_BINDING_REVISION) : 1L,
                tag.contains(TAG_NAME) ? tag.getString(TAG_NAME) : null));
    }

    /**
     * Writes this binding onto a key stack's root tag.
     *
     * <p>Flat on the root rather than nested, because that is where a key's own binding belongs: the
     * nested {@code CopiedKey} compound is a <em>mold's</em> record of a key, which is a different
     * thing with a different lifetime.
     */
    public void writeTo(CompoundTag stackTag) {
        if (stackTag == null) {
            return;
        }
        stackTag.putUUID(TAG_LOCK_ID, lockId);
        stackTag.putLong(TAG_BINDING_REVISION, bindingRevision);
        if (name != null) {
            stackTag.putString(TAG_NAME, name);
        } else {
            stackTag.remove(TAG_NAME);
        }
    }

    /** The binding on a key stack's root tag, if it has one. */
    public static Optional<KeyBinding> read(@Nullable CompoundTag stackTag) {
        return load(stackTag);
    }

    /** Clears a key's binding, turning it back into a blank. */
    public static void clear(@Nullable CompoundTag stackTag) {
        if (stackTag == null) {
            return;
        }
        stackTag.remove(TAG_LOCK_ID);
        stackTag.remove(TAG_BINDING_REVISION);
        stackTag.remove(TAG_NAME);
    }

    /** Whether a key stack's tag carries any binding at all. */
    public static boolean bound(@Nullable CompoundTag stackTag) {
        return stackTag != null && stackTag.hasUUID(TAG_LOCK_ID);
    }
}
