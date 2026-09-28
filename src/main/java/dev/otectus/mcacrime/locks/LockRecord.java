package dev.otectus.mcacrime.locks;

import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * One lock's identity and state (§3.7).
 *
 * <p>The identity is the {@code lockId}; blocks and padlock entities store nothing but that id, and
 * a key stack stores {@code (lockId, bindingRevision, name)}. Rekeying increments
 * {@link #bindingRevision} and thereby invalidates every old copy of the key without touching a
 * single coordinate — which is also why a stale lockpick session pinned to
 * {@code (lockId, bindingRevision)} cannot open a replacement lock placed where the old one was.
 *
 * <p>Key copies compare their binding revision with {@link #accepts(long)}. Upstream compares its
 * bound UUIDs with {@code ==}, which works only by accident of interning; nothing here does.
 *
 * @param lockId          the identity keys are bound to
 * @param bindingRevision bumped by a rekey; old key copies stop working
 * @param target          where the lock currently is
 * @param owner           who set it, when a player did
 * @param authorityVillage the village that owns it, when an authority does
 * @param locked          whether it is locked right now
 * @param reinforced      whether it has been reinforced (a harder pick profile)
 * @param revision        bumped on every change
 */
public record LockRecord(
        UUID lockId,
        long bindingRevision,
        LockTarget target,
        @Nullable UUID owner,
        int authorityVillage,
        boolean locked,
        boolean reinforced,
        long revision) {

    public LockRecord {
        target = target == null ? LockTarget.none() : target;
        bindingRevision = Math.max(1L, bindingRevision);
        revision = Math.max(0L, revision);
    }

    /** A new lock, locked, bound at revision 1. */
    public static LockRecord of(UUID lockId, LockTarget target, @Nullable UUID owner) {
        return new LockRecord(lockId, 1L, target, owner, 0, true, false, 1L);
    }

    public Optional<UUID> ownerId() {
        return Optional.ofNullable(owner);
    }

    /** Whether a key bound at {@code keyBindingRevision} still opens this lock. */
    public boolean accepts(long keyBindingRevision) {
        return keyBindingRevision == bindingRevision;
    }

    /**
     * This lock rekeyed: every existing key copy stops working.
     *
     * <p>A revision bump rather than a new id, because the id is what the block, the padlock entity
     * and every audit reference point at. Changing it would orphan all three.
     */
    public LockRecord rekeyed() {
        return new LockRecord(lockId, bindingRevision + 1L, target, owner, authorityVillage, locked,
                reinforced, revision + 1L);
    }

    public LockRecord locked(boolean value) {
        if (value == locked) {
            return this;
        }
        return new LockRecord(lockId, bindingRevision, target, owner, authorityVillage, value,
                reinforced, revision + 1L);
    }

    public LockRecord reinforced(boolean value) {
        if (value == reinforced) {
            return this;
        }
        return new LockRecord(lockId, bindingRevision, target, owner, authorityVillage, locked, value,
                revision + 1L);
    }

    /** This lock pointed at a new target; the binding is untouched, so keys keep working. */
    public LockRecord movedTo(LockTarget newTarget) {
        return new LockRecord(lockId, bindingRevision, newTarget == null ? LockTarget.none() : newTarget,
                owner, authorityVillage, locked, reinforced, revision + 1L);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("lockId", lockId);
        tag.putLong("bindingRevision", bindingRevision);
        tag.put("target", target.save());
        if (owner != null) tag.putUUID("owner", owner);
        tag.putInt("village", authorityVillage);
        tag.putBoolean("locked", locked);
        tag.putBoolean("reinforced", reinforced);
        tag.putLong("revision", revision);
        return tag;
    }

    public static Optional<LockRecord> load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.hasUUID("lockId")) {
            return Optional.empty();
        }
        return Optional.of(new LockRecord(
                tag.getUUID("lockId"),
                tag.getLong("bindingRevision"),
                LockTarget.load(tag.getCompound("target")),
                tag.hasUUID("owner") ? tag.getUUID("owner") : null,
                tag.getInt("village"),
                tag.getBoolean("locked"),
                tag.getBoolean("reinforced"),
                tag.getLong("revision")));
    }
}
